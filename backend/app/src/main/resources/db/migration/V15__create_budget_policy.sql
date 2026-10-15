-- =============================================================================
-- FitAgent agent-v1 迁移 V15：budget_policy（用户预算策略）
-- 依赖 V1、V10。
--
-- 这是 BYOK 下唯一能挡住"跑飞"的闸门（docs/agent-v1-design.md §6.3：BudgetGate 不是默认放行）。
-- 语义必须钉死，否则会变成假保护：
--   max_task_cost / max_daily_cost 为 NULL 表示"该维度不设上限"，不是 0、不是"不限=随便花"里的 0；
--   两者同时为 NULL 时 BudgetGate 仍然拒绝发起调用——一行没有任何上限的策略等价于"默认放行"，
--   而设计明确禁止默认放行，因此策略必须至少给出一个上限才可用（代码侧校验，见 BudgetGate）。
--   enabled=false 同样拒绝发起：关掉预算控制不等于放行控制，用户想花就得显式开着。
--
-- currency 进唯一键：不同币种是彼此独立的账户，永不相减、不猜汇率（设计 §6.3）。
-- budget_timezone 固定 Asia/Shanghai（设计 §7 V15），因此本列没有任何写入路径，
-- 日账本归属完全由服务端按该时区从"调用开始时刻"推导，客户端不传日期。
-- max_model_calls / max_tool_calls / max_output_tokens 是工程闸门（与 RunLimits 同源），
-- 不是健康阈值，也不是供应商侧的硬额度。
-- =============================================================================

CREATE TABLE `budget_policy` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id`       BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `currency`          CHAR(3) NOT NULL COMMENT '限额币种（服务端规范化大写）；必须与 model_price.currency 一致才可比',
  `max_task_cost`     DECIMAL(20,10) NULL COMMENT '单次任务（一个 run 的全部模型调用之和）上限；NULL 表示该维度不设限',
  `max_daily_cost`    DECIMAL(20,10) NULL COMMENT '当日上限（按 budget_timezone 的业务日）；NULL 表示该维度不设限',
  `budget_timezone`   VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '业务日推导时区；本轮固定 Asia/Shanghai，无写入路径',
  `max_model_calls`   TINYINT UNSIGNED NOT NULL DEFAULT 4 COMMENT '单个 run 允许的模型调用次数上限；工程闸门',
  `max_tool_calls`    TINYINT UNSIGNED NOT NULL DEFAULT 8 COMMENT '单个 run 允许的工具执行次数上限；工程闸门',
  `max_output_tokens` INT UNSIGNED NOT NULL DEFAULT 1024 COMMENT '保守预占使用的输出上限（不取 model_catalog.max_output_tokens，那是供应商硬上限）',
  `enabled`           BOOLEAN NOT NULL DEFAULT TRUE COMMENT '策略是否生效；false 时 BudgetGate 拒绝发起调用，而不是无视预算',
  `lock_version`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 一个用户在一个供应商的一个币种上只有一份在用策略
  UNIQUE KEY `uq_budget_policy_1` (`user_id`, `provider_id`, `currency`),
  UNIQUE KEY `uq_budget_policy_2` (`id`, `user_id`),
  KEY `idx_budget_policy_3` (`user_id`),
  CONSTRAINT `ck_budget_policy_0` CHECK (`max_task_cost` IS NULL OR `max_task_cost` > 0),
  CONSTRAINT `ck_budget_policy_1` CHECK (`max_daily_cost` IS NULL OR `max_daily_cost` > 0),
  CONSTRAINT `ck_budget_policy_2` CHECK (`max_model_calls` > 0),
  CONSTRAINT `ck_budget_policy_3` CHECK (`max_tool_calls` > 0),
  CONSTRAINT `ck_budget_policy_4` CHECK (`max_output_tokens` > 0),
  CONSTRAINT `fk_budget_policy_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_budget_policy_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户预算策略（不是默认放行）';
