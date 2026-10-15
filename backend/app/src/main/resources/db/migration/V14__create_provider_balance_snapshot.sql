-- =============================================================================
-- FitAgent agent-v1 迁移 V14：provider_balance_snapshot（供应商余额快照）
-- 依赖 V1、V10。
--
-- 语义先说清楚（docs/agent-v1-protocol.md §8）：
--   1. 这是供应商账户级余额，不是单把 Key 的额度，也可能被本应用之外的调用改变；
--      因此它只是"上一次问供应商时它怎么说的"，绝不参与预算判定（预算看的是本应用账本）。
--   2. 官方字段是 is_available（余额是否足以继续调用），is_expired 不存在于当前任何文档页，
--      旧设计包写错了，本表不予实现。界面上的"陈旧"标记由应用推导
--      （fetched_at 与当前时间之差），不是库存列。
--   3. 三个金额官方返回字符串，落库为 DECIMAL(20,10)，不用 double（协议 §8 明确要求 BigDecimal 绑定）。
--   4. NULL 表示"未提供该分项"，与 0 完全不同：0 是"确实没钱了"，NULL 是"不知道"。
--      查询失败时不落新行，因此"上一次的值 + 陈旧标记"始终可展示，永不被 0 覆盖。
--
-- 一个 balance_infos[] 元素一行（协议 §8 的 currency 可为 CNY 或 USD，可能同时出现两个币种），
-- 因此唯一键必须含 currency：否则同一次抓取的第二种币种会撞键，把一次正常查询变成失败。
-- 跨币种永不相减（docs/agent-v1-design.md §6.3）。
--
-- source_http_status / source_error_code 记录这一行的出处：只有成功的抓取才会落行，
-- 因此它们保存的是"被接受的那次响应"的 HTTP 状态与供应商自报结果码，用于事后追溯，
-- 失败信息不进表（失败不留痕是本表的约定，见上）。
-- =============================================================================

CREATE TABLE `provider_balance_snapshot` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属用户（谁的 Key 问出来的余额）；从登录上下文取得',
  `provider_id`      BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `currency`         CHAR(3) NOT NULL COMMENT '该分项币种（CNY / USD）；服务端规范化大写，与预算币种不同时分开显示',
  `total_balance`    DECIMAL(20,10) NULL COMMENT '总余额；NULL 表示供应商未给出该分项，不当 0',
  `granted_balance`  DECIMAL(20,10) NULL COMMENT '赠送余额；官方扣费顺序是先赠送后充值（协议 §7）',
  `topped_up_balance` DECIMAL(20,10) NULL COMMENT '充值余额',
  `is_available`     BOOLEAN NULL COMMENT '供应商报告的"余额是否足以继续调用"；NULL 表示响应里没有该字段',
  `fetched_at`       DATETIME(3) NOT NULL COMMENT 'UTC 抓取时刻；同时是"最新快照"的排序依据',
  `source_http_status` SMALLINT UNSIGNED NULL COMMENT '被接受的响应 HTTP 状态（成功路径恒 200）',
  `source_error_code`  VARCHAR(64) NULL COMMENT '供应商在 200 响应内自报的结果码原文；没有则为 NULL，不猜测',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 同一次抓取（同一 fetched_at）内一个币种只有一行；该索引同时服务
  -- "WHERE user_id=? AND provider_id=? AND currency=? ORDER BY fetched_at DESC LIMIT 1"（8.4 可反向扫描）
  UNIQUE KEY `uq_provider_balance_snapshot_1` (`user_id`, `provider_id`, `currency`, `fetched_at`),
  UNIQUE KEY `uq_provider_balance_snapshot_2` (`id`, `user_id`),
  KEY `idx_provider_balance_snapshot_3` (`user_id`),
  CONSTRAINT `ck_provider_balance_snapshot_0` CHECK (`total_balance` IS NULL OR `total_balance` >= 0),
  CONSTRAINT `ck_provider_balance_snapshot_1` CHECK (`granted_balance` IS NULL OR `granted_balance` >= 0),
  CONSTRAINT `ck_provider_balance_snapshot_2` CHECK (`topped_up_balance` IS NULL OR `topped_up_balance` >= 0),
  CONSTRAINT `fk_provider_balance_snapshot_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_provider_balance_snapshot_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商账户余额快照（失败不清零）';
