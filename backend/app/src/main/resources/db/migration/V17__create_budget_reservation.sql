-- =============================================================================
-- FitAgent agent-v1 迁移 V17：budget_reservation（逐次调用的预占台账）
-- 依赖 V1、V10、V16。
--
-- run_id 是普通 BIGINT UNSIGNED 列，没有外键：agent_run 本轮还不存在（设计 §7 把它排在 V18）。
-- 因此本表只保证"同一个 (run_id, attempt_no) 只有一条预占"，跨表归属由应用侧
-- WHERE run_id = ? AND user_id = ? 的双条件限定；agent_run 落地后应补一条复合外键（届时另出迁移，
-- 不回改本文件——迁移一律向前）。
--
-- 结算幂等的数据库防线在这里，不在 model_call（该表本轮不建）：
--   status 是唯一状态机（RESERVED → SETTLED / UNCERTAIN / RELEASED），
--   任何转移都用 "WHERE id=? AND user_id=? AND status=<期望值>" 的比较并交换完成，
--   影响 0 行就说明这次回调重复或顺序错了，账本不会动第二次。
--   ck_2 把 SETTLED 与"实际金额 + 落定时刻"绑死：没有这两个值就不允许自称已结算。
--
-- estimate_quality 的取值口径（协议 §7：节假日日历未核实 → 预占恒按 PEAK 档）：
--   CONSERVATIVE 预占时的保守估算（输入全按未命中价、输出按 max_output_tokens 全价、档位取 PEAK）
--   ACTUAL       已按本次真实 usage 核算（仍不称为"供应商账单"，供应商结算记录才是最终依据）
--   UNKNOWN      usage 不明：保留估算额，既不释放也不当已花
-- reserved_amount 落库后不再改写——它是"当初占了多少"的凭证，改了日账户的分账就复现不出来。
--
-- 版本号说明：设计 §7 的排期把 budget_reservation 记作 V19、chat_conversation 记作 V17。
-- 本表与 modelaccess/billing 的其余表必须连续（V10–V17），故占用 V17；
-- chat_conversation / agent_run / model_call / agent_step / chat_message 从 V18 起顺延，
-- 不要再新建第二个 V17（Flyway 同版本号会直接启动失败）。
-- =============================================================================

CREATE TABLE `budget_reservation` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得，所有查询都以它为条件',
  `run_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属 run；agent_run 本轮不存在，故不建外键，仅做逻辑归属',
  `attempt_no`       INT UNSIGNED NOT NULL COMMENT '该 run 内的第几次模型调用（从 1 起）；受 budget_policy.max_model_calls 约束',
  `provider_id`      BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `daily_usage_id`   BIGINT UNSIGNED NOT NULL COMMENT '所归属的当日账户行；FK → budget_daily_usage.id，预占/结算都锁这一行',
  `currency`         CHAR(3) NOT NULL COMMENT '币种；必须与当日账户行一致，跨币种永不相减',
  `budget_date`      DATE NOT NULL COMMENT '业务预算日（调用开始时刻按预算时区推导），冗余自账户行便于对账',
  `reserved_amount`  DECIMAL(20,10) NOT NULL COMMENT '本次预占额（保守估算）；写入后不改写',
  `settled_amount`   DECIMAL(20,10) NULL COMMENT '本次实际核算额；NULL 表示尚未落定（不是 0）',
  `estimate_quality` ENUM('CONSERVATIVE','ACTUAL','UNKNOWN') NOT NULL COMMENT '金额质量：保守估算 / 已按实际 usage 核算 / 用量不明',
  `status`           ENUM('RESERVED','SETTLED','UNCERTAIN','RELEASED') NOT NULL DEFAULT 'RESERVED' COMMENT '预占状态机；转移用比较并交换完成',
  `settled_at`       DATETIME(3) NULL COMMENT 'UTC 落定时刻；仅 SETTLED 非空',
  `lock_version`     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 同一次调用的重复预占请求不会产生两条账（重试走幂等，不靠应用记得查）
  UNIQUE KEY `uq_budget_reservation_1` (`run_id`, `attempt_no`),
  UNIQUE KEY `uq_budget_reservation_2` (`id`, `user_id`),
  -- 单次任务费用的求和形态：一个 run 的全部预占
  KEY `idx_budget_reservation_3` (`user_id`, `run_id`),
  -- 当日账户的台账（排查双花与对账用）
  KEY `idx_budget_reservation_4` (`daily_usage_id`),
  CONSTRAINT `ck_budget_reservation_0` CHECK (`attempt_no` > 0),
  CONSTRAINT `ck_budget_reservation_1` CHECK (`reserved_amount` >= 0),
  CONSTRAINT `ck_budget_reservation_2` CHECK (`settled_amount` IS NULL OR (`settled_amount` >= 0 AND `settled_at` IS NOT NULL)),
  CONSTRAINT `ck_budget_reservation_3` CHECK (`status` <> 'SETTLED' OR `estimate_quality` = 'ACTUAL'),
  CONSTRAINT `ck_budget_reservation_4` CHECK (`status` <> 'RELEASED' OR `settled_amount` IS NULL),
  CONSTRAINT `fk_budget_reservation_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_budget_reservation_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`),
  CONSTRAINT `fk_budget_reservation_2` FOREIGN KEY (`daily_usage_id`, `user_id`) REFERENCES `budget_daily_usage` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐次调用的预算预占台账';
