-- =============================================================================
-- FitAgent agent-v1 迁移 V20：model_call（逐次远程调用的台账）
-- 依赖 V11、V12、V17、V19。对应设计 §7 的 model_call 与 §6.3 的闸门时序。
--
-- 状态机就是"请求到底可能已经发出去了没有"的唯一口径（设计 §5 第 6 条、§10）：
--   PREPARED  已过预算闸门并落台账，请求尚未交给传输层
--   SENT      已交给传输层（此后任何失败都可能已计费）
--   SUCCEEDED 拿到可解析的响应
--   UNKNOWN   已送达但结果不可读（读超时 / 断连 / 无法理解的响应体）
--   FAILED    可证明从未送达（连接建立前失败）或供应商明确拒绝
-- run 的恢复判定只读这张表：存在 PREPARED/SENT/UNKNOWN 即"可能有已发送的调用"→
-- run 转 FAILED + UNKNOWN_CHARGE，预占留在 UNCERTAIN，**绝不自动重发**；
-- 一张都没有才允许把 run 退回 QUEUED 重新领取（§10）。
--
-- UNIQUE(reservation_id) 是"一次预占只结算一次"的数据库防线（设计 §6.3 结算幂等）：
-- 同一笔预占想挂两次调用，第二条 INSERT 就撞索引，不需要应用记得查。
--
-- usage_payload 存供应商 usage 原文，四个 token 分项是它的结构化视图：
--   缺分项填 NULL，**不填 0**（协议 §6：0 会让一次用量不明的调用看起来免费）；
--   reasoning_tokens 是 completion 的细分项，只登记不再计价（§6.2）。
-- calculated_cost / currency 为本次核算金额与币种；币种与 run 的预算币种必须同源才可比。
--
-- credential_version 冗余自 run：事后追溯"哪一次调用用的是哪一把 Key 的版本"，不含任何材料。
-- provider_request_id 是供应商回传的标识（本应用没有外键指向它），只用于对账排查。
-- =============================================================================

CREATE TABLE `model_call` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`             BIGINT UNSIGNED NOT NULL COMMENT '所属用户；与 run 同属，所有查询双条件限定',
  `run_id`              BIGINT UNSIGNED NOT NULL COMMENT '所属 run；FK → agent_run(id, user_id)',
  `reservation_id`      BIGINT UNSIGNED NULL COMMENT '对应预占台账；FK → budget_reservation(id, user_id)；UNIQUE 保证一次预占只结算一次',
  `attempt_no`          INT UNSIGNED NOT NULL COMMENT '该 run 内第几次远程调用（从 1 起）；与预占的 attempt_no 同口径',
  `model_id`            BIGINT UNSIGNED NOT NULL COMMENT '实际使用的模型目录行；FK → model_catalog.id',
  `price_id`            BIGINT UNSIGNED NULL COMMENT '核算所用的 model_price 行（版本化报价快照）；FK → model_price.id；未核算时为 NULL',
  `credential_version`  INT UNSIGNED NULL COMMENT '本次调用绑定的凭据版本；不含任何密钥材料',
  `provider_request_id` VARCHAR(128) NULL COMMENT '供应商回带的请求标识，仅用于对账排查',
  `status`              ENUM('PREPARED','SENT','SUCCEEDED','UNKNOWN','FAILED') NOT NULL DEFAULT 'PREPARED' COMMENT '调用状态机；UNKNOWN 与 FAILED 的区别就是"是否可能已计费"',
  `usage_payload`       JSON NULL COMMENT '供应商 usage 原文（含未识别字段也照存）；NULL = 没有回报任何用量',
  `prompt_cache_hit_tokens`     INT UNSIGNED NULL COMMENT '缓存命中输入 token；NULL 表示未回报（不是 0）',
  `prompt_cache_miss_tokens`    INT UNSIGNED NULL COMMENT '未命中输入 token；NULL 表示未回报（不是 0）',
  `completion_tokens`           INT UNSIGNED NULL COMMENT '输出 token（含 reasoning 细分）',
  `reasoning_tokens`            INT UNSIGNED NULL COMMENT '思考 token：completion 的细分项，不参与计价',
  `calculated_cost`     DECIMAL(20,10) NULL COMMENT '本次核算金额；NULL = 不可核算，绝不写 0',
  `currency`            CHAR(3) NULL COMMENT '金额币种（ISO-4217 大写）；跨币种永不相减',
  `sent_at`             DATETIME(3) NULL COMMENT 'UTC 交给传输层的时刻；非 NULL 即"可能已送达"',
  `finished_at`         DATETIME(3) NULL COMMENT 'UTC 本次调用得到结论的时刻',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_call_1` (`run_id`, `attempt_no`),
  -- 一笔预占只能挂一次调用（结算幂等的数据库防线）
  UNIQUE KEY `uq_model_call_2` (`reservation_id`),
  UNIQUE KEY `uq_model_call_3` (`id`, `user_id`),
  -- run 的"是否存在可能已发送的调用"扫描（恢复判定的唯一读取形态）
  KEY `idx_model_call_4` (`user_id`, `run_id`, `status`),
  CONSTRAINT `ck_model_call_0` CHECK (`attempt_no` > 0),
  CONSTRAINT `ck_model_call_1` CHECK (`calculated_cost` IS NULL OR `calculated_cost` >= 0),
  -- SENT/SUCCEEDED/UNKNOWN 都意味着"已经交给传输层"，没有 sent_at 就无从证明
  CONSTRAINT `ck_model_call_2` CHECK (`status` NOT IN ('SENT','SUCCEEDED','UNKNOWN') OR `sent_at` IS NOT NULL),
  CONSTRAINT `fk_model_call_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_model_call_1` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_model_call_2` FOREIGN KEY (`reservation_id`, `user_id`) REFERENCES `budget_reservation` (`id`, `user_id`),
  CONSTRAINT `fk_model_call_3` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`),
  CONSTRAINT `fk_model_call_4` FOREIGN KEY (`price_id`) REFERENCES `model_price` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐次远程模型调用台账';
