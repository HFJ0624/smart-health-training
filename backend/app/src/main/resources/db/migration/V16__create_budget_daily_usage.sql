-- =============================================================================
-- FitAgent agent-v1 迁移 V16：budget_daily_usage（当日预算账户）
-- 依赖 V1、V10、V15（同一组归属键，但本表不引用策略行：策略被删也不该让账本悬空）。
--
-- 三列分账是这套预算能"不重复扣账"的关键（docs/agent-v1-design.md §6.3）：
--   settled_cost            —— 已按实际 usage 核算落定的钱（真金白银）
--   reserved_cost           —— 已预占、请求在途或结果还好的钱（保守估算额）
--   uncertain_reserved_cost —— 已发出但 usage 不明的钱：保留估算额，不重发、也不归零
-- 可用额度 = 限额 − (settled + reserved + uncertain_reserved)。三者缺一都会变成"默认放行"，
-- 尤其是 uncertain：把它当 0 就等于允许用户为一次可能已扣费的调用再花一次预算。
--
-- 本行是并发预占的串行点：BudgetGate 在同一短事务里 INSERT … ON DUPLICATE KEY 分配该行
-- 再 SELECT … FOR UPDATE 锁住它，因此同一天账户上的两次并发预占不会互相看不见（不会双花）。
-- 三列都 >= 0 是硬约束：转移公式（reserved→settled 等）一旦写错，数据库会当场拒绝而不是
-- 悄悄给出一个负数额度。
--
-- budget_date 由服务端按 budget_policy.budget_timezone（本轮 Asia/Shanghai）从"调用开始时刻"推导，
-- 客户端不传；跨午夜的长任务里每次调用各归自己的开始日，单次任务上限仍覆盖全部调用（设计 §6.3）。
-- =============================================================================

CREATE TABLE `budget_daily_usage` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`               BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `currency`              CHAR(3) NOT NULL COMMENT '账户币种；不同币种是不同账户行，永不相减、不猜汇率',
  `budget_date`           DATE NOT NULL COMMENT '业务预算日；服务端按用户预算时区从调用开始时刻推导',
  `settled_cost`          DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '已核算落定费用',
  `reserved_cost`         DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '在途预占（保守估算额）',
  `uncertain_reserved_cost` DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '用量不明的预占：仍占用额度，不重发',
  `lock_version`          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；并发预占的串行点，更新时递增',
  `created_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 一个用户/供应商/币种/业务日只有一个账户行；该索引同时覆盖"取当日账户"的 FOR UPDATE 查询
  UNIQUE KEY `uq_budget_daily_usage_1` (`user_id`, `provider_id`, `currency`, `budget_date`),
  UNIQUE KEY `uq_budget_daily_usage_2` (`id`, `user_id`),
  -- 日账本页面的查询形态：某人某供应商按日期倒序
  KEY `idx_budget_daily_usage_3` (`user_id`, `provider_id`, `budget_date`),
  CONSTRAINT `ck_budget_daily_usage_0` CHECK (`settled_cost` >= 0),
  CONSTRAINT `ck_budget_daily_usage_1` CHECK (`reserved_cost` >= 0),
  CONSTRAINT `ck_budget_daily_usage_2` CHECK (`uncertain_reserved_cost` >= 0),
  CONSTRAINT `fk_budget_daily_usage_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_budget_daily_usage_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当日预算账户（三列分账）';
