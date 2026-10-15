-- =============================================================================
-- FitAgent agent-v1 迁移 V19：agent_run（一次提问的后台执行任务）
-- 依赖 V1、V11、V13、V18。对应设计 §7、§10。
--
-- 三条数据库级不变式（不是应用层的"记得检查"）：
--
-- 1. 单会话只有一个活动 run —— `active_conversation_id` 是 STORED 生成列，
--    与 user_goal.active_user_id 完全同一手法（AGENTS.md：任何 INSERT/UPDATE 都不能带它）：
--      status ∈ (QUEUED,RUNNING) → 取 conversation_id，否则 NULL。
--    UNIQUE 里 NULL 不参与冲突，所以终态 run 再多也不挡；同一个会话的第二个 QUEUED/RUNNING 直接撞索引。
--    应用层据此"捕获重复键 → RUN_ALREADY_ACTIVE"，而不是先查再插（先查再插在并发下必然两个都通过）。
--
-- 2. 幂等 —— UNIQUE(user_id, idempotency_key)。同键同 request_hash 返回同一 run；
--    同键不同 hash 由应用判定为 IDEMPOTENCY_KEY_CONFLICT，绝不返回另一个问题的结果。
--    request_hash = SHA-256(conversationId | 规范化问题文本 | 语言/日期参数) 的 64 位十六进制，
--    **不含任何凭据材料**（设计 §6.1：Key 不进库、不进哈希输入、不进日志）。
--
-- 3. 归属 —— (id, user_id) 复合唯一 + 子表复合外键；run_uuid 是对外标识（BIGINT 主键不外泄自增顺序）。
--
-- 费用三列的口径（设计 §6.2、§11.1 规范化决定第 1 条）：
--   estimated_cost  保守预占口径的预估（发送前显示；NULL = 当时无法核算，不是 0）
--   settled_cost    已按真实 usage 结算之和
--   uncertain_cost  已发出但用量不明的估算之和（不归零、不重发）
-- 三列都只在 run 行上，AgentAnswer 里没有费用字段——两处都能放就一定漂移。
-- `currency` 独立成列：响应必须带币种，而跨币种永不相减（设计 §6.3），不能靠解析 JSON 猜。
--
-- result_payload 存**完整** AgentAnswer（事实卡片、来源、缺失项、data_revision、stop_reason），
-- 页面重进读同一份持久化结果，不依赖内存、也不拿 agent_step.result_summary 的截断文本冒充。
-- 事实快照一旦写入不再被静默重写，费用状态仍可更新（设计 §7）。
--
-- worker_id / lease_expires_at 是恢复判定的依据（设计 §10）：领取用条件更新（0 行即放弃），
-- 心跳只续租 lease_expires_at；失去 Worker 的 RUNNING 行由启动扫描器按 model_call 状态裁决。
-- =============================================================================

CREATE TABLE `agent_run` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；对外用 run_uuid，接口响应里的 runId 是它的字符串形式',
  `run_uuid`               CHAR(36) NOT NULL COMMENT '对外的任务标识（服务端生成的小写 UUID）',
  `user_id`                BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `conversation_id`        BIGINT UNSIGNED NOT NULL COMMENT '所属会话；FK → chat_conversation(id, user_id)',
  `idempotency_key`        CHAR(36) NOT NULL COMMENT '客户端幂等键（小写规范化 UUID）；同键同 hash 返回同一 run',
  `request_hash`           CHAR(64) NOT NULL COMMENT 'SHA-256(会话号|规范化问题文本|语言与日期参数) 十六进制；不含凭据',
  `credential_id`          BIGINT UNSIGNED NULL COMMENT 'run 创建时绑定的凭据行；FK → model_credential(id, user_id)；无可用凭据时为 NULL',
  `credential_version`     INT UNSIGNED NULL COMMENT '绑定的凭据版本；执行中不悄悄换 Key（设计 §6.1）',
  `model_id`               BIGINT UNSIGNED NULL COMMENT '模型目录行；FK → model_catalog.id；能力与输出上限只可能来自这张表',
  `status`                 ENUM('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','BUDGET_STOPPED','LIMIT_STOPPED','DEADLINE_STOPPED','WAITING_CREDENTIAL','COST_UNKNOWN') NOT NULL DEFAULT 'QUEUED' COMMENT 'run 状态机；除 QUEUED/RUNNING 外都是终态',
  `active_conversation_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` IN ('QUEUED','RUNNING') THEN `conversation_id` ELSE NULL END) STORED COMMENT '数据库派生：会话内活动 run 闸门；ORM 不声明、任何写入都不带它',
  `budget_snapshot`        JSON NOT NULL COMMENT 'run 创建时的预算侧快照（策略上限、币种、PEAK 单价、保守估算）；执行时的真实闸门仍逐次生效，本列只用于展示与追溯',
  `currency`               CHAR(3) NULL COMMENT '预算币种（ISO-4217 大写）；与 model_price/budget_policy 同源，跨币种永不相减',
  `context_revision`       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'run 开始时的 user_account.data_revision；装配 AgentContext 与陈旧判定都以它为准（结束时再读一次比对）',
  `result_payload`         JSON NULL COMMENT '完整 AgentAnswer 快照（终态时写入）；事实不被静默重写，页面重进读同一份',
  `stop_reason`            VARCHAR(32) NULL COMMENT '停止原因机器码（agent.core.StopReason 名）；未识别值前端原样显示',
  `error_code`             VARCHAR(64) NULL COMMENT '稳定机器码（如 UNKNOWN_CHARGE / CREDENTIAL_MISSING）；不解析文案分支',
  `estimated_cost`         DECIMAL(20,10) NULL COMMENT '保守估算额（发送前）；NULL 表示当时无法核算，绝不写 0 冒充免费',
  `settled_cost`           DECIMAL(20,10) NULL COMMENT '已结算实际费用之和；NULL 表示尚无任何已核算调用',
  `uncertain_cost`         DECIMAL(20,10) NULL COMMENT '已发出但用量不明的估算之和；不归零、不重发（设计 §6.2）',
  `cancel_requested_at`    DATETIME(3) NULL COMMENT 'UTC 用户请求取消时刻；幂等取消只写第一次',
  `worker_id`              VARCHAR(64) NULL COMMENT '领取本 run 的 Worker 标识；条件更新写入，恢复判定据此识别失联',
  `lease_expires_at`       DATETIME(3) NULL COMMENT 'UTC 租约到期时刻；心跳续租。过期且非终态 = 失去 Worker',
  `started_at`             DATETIME(3) NULL COMMENT 'UTC 真正开始执行时刻（QUEUED→RUNNING）',
  `finished_at`            DATETIME(3) NULL COMMENT 'UTC 终态时刻；非终态恒为 NULL',
  `lock_version`           INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；状态转移用条件更新',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 会话内活动 run 的唯一闸门（生成列 + UNIQUE，见文件头说明）
  UNIQUE KEY `uq_agent_run_1` (`active_conversation_id`),
  -- 幂等：同一用户的同一个键只对应一个 run
  UNIQUE KEY `uq_agent_run_2` (`user_id`, `idempotency_key`),
  UNIQUE KEY `uq_agent_run_3` (`run_uuid`),
  -- 子表（model_call / agent_step / chat_message）复合外键的目标
  UNIQUE KEY `uq_agent_run_4` (`id`, `user_id`),
  -- 会话历史按时间倒序；run 列表与"该会话是否有活动 run"的读取形态
  KEY `idx_agent_run_5` (`user_id`, `conversation_id`, `created_at`),
  -- 启动恢复扫描：非终态且租约过期
  KEY `idx_agent_run_6` (`status`, `lease_expires_at`),
  CONSTRAINT `ck_agent_run_0` CHECK (`credential_version` IS NULL OR `credential_version` > 0),
  -- 非终态不得自称已结束；终态与"仍在排队/执行"由同一列判定，不留两套口径
  CONSTRAINT `ck_agent_run_1` CHECK (`status` IN ('QUEUED','RUNNING') OR `finished_at` IS NOT NULL),
  CONSTRAINT `ck_agent_run_2` CHECK (`status` NOT IN ('QUEUED','RUNNING') OR `finished_at` IS NULL),
  CONSTRAINT `ck_agent_run_3` CHECK (`estimated_cost` IS NULL OR `estimated_cost` >= 0),
  CONSTRAINT `ck_agent_run_4` CHECK (`settled_cost` IS NULL OR `settled_cost` >= 0),
  CONSTRAINT `ck_agent_run_5` CHECK (`uncertain_cost` IS NULL OR `uncertain_cost` >= 0),
  CONSTRAINT `fk_agent_run_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_agent_run_1` FOREIGN KEY (`conversation_id`, `user_id`) REFERENCES `chat_conversation` (`id`, `user_id`),
  CONSTRAINT `fk_agent_run_2` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`),
  CONSTRAINT `fk_agent_run_3` FOREIGN KEY (`credential_id`, `user_id`) REFERENCES `model_credential` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次提问的后台执行任务';
