-- =============================================================================
-- FitAgent agent-v1 迁移 V11：model_catalog（模型能力目录）
-- 依赖 V10（model_provider）。
--
-- 取值依据：docs/agent-v1-protocol.md §1 —— 当前只有 deepseek-flash 与 deepseek-v4-pro
-- （deepseek-chat / deepseek-reasoner 已于 2026-07-24 停用）。
-- 本表是这些事实的唯一存放处：代码里不写死模型名、上下文与输出上限
-- （docs/agent-v1-design.md §5 第 8 条）。因此本轮没有目录种子数据也无妨——
-- 读不到 ACTIVE 模型就报 MODEL_NOT_CONFIGURED，绝不回退到内置默认模型。
--
-- 三个 supports_* 是布尔开关而不是提示文字：
--   supports_tools 决定工具调用能否开启；supports_json 决定结构化输出；
--   supports_vision 本轮没有任何图片入口（设计§1"不做图片解析"），列存在只是因为
--   协议核查已给出真实取值，页面据此显示"本项目不使用"。
--
-- capability_snapshot 存核查到的原始字段快照（含来源与核查上下文），
-- 与三个派生布尔列并存的理由：派生列可被 SQL 谓词使用，快照保留"我们据以得出该结论的原文"，
-- 事后发现派生口径错了可以回溯。形状由 docs/agent-v1-protocol.md 约束，不在 DDL 里用函数 CHECK 强检
-- （本机无法连到 8.4 验证函数式 CHECK，宁可不写也不写一条可能让迁移失败的约束）。
-- =============================================================================

CREATE TABLE `model_catalog` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `provider_id`        BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `model_code`         VARCHAR(80)  NOT NULL COMMENT '请求体 model 字段的取值（如 deepseek-flash）；列保持表默认 CI 排序规则，服务端规范化后精确匹配，不做近似匹配',
  `display_name`       VARCHAR(120) NOT NULL COMMENT '界面显示名（可为官方"实际模型版本"，如 DeepSeek-V4.1-Flash）',
  `supports_vision`    BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持图片输入；本轮无图片入口，取值仅作真实登记',
  `supports_tools`     BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持原生 tool calls；不支持的工具调用不能进入执行循环',
  `supports_json`      BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持 JSON Output',
  `max_context_tokens` INT UNSIGNED NOT NULL COMMENT '上下文窗口上限（协议 §1：1048576）；用于历史裁剪，不用于放宽任何业务阈值',
  `max_output_tokens`  INT UNSIGNED NOT NULL COMMENT '单次输出上限（协议 §1：393216）；预算保守预占按 budget_policy.max_output_tokens，不取本列',
  `capability_snapshot` JSON NOT NULL COMMENT '核查原文快照；来源与字段口径见 docs/agent-v1-protocol.md §1，NOT NULL 强制每次登记都带出处',
  `source_checked_on`  DATE NOT NULL COMMENT '能力核查日期（不是创建时间）；过期后由运维重新核对',
  `status`             ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '模型状态；DISABLED 时不可选',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_catalog_1` (`provider_id`, `model_code`),
  -- 目录读取的固定形态：给定供应商取 ACTIVE 模型，按核查日期与 id 确定性择一
  KEY `idx_model_catalog_2` (`provider_id`, `status`, `source_checked_on`),
  CONSTRAINT `ck_model_catalog_0` CHECK (`max_context_tokens` > 0),
  CONSTRAINT `ck_model_catalog_1` CHECK (`max_output_tokens` > 0),
  CONSTRAINT `ck_model_catalog_2` CHECK (`max_output_tokens` <= `max_context_tokens`),
  CONSTRAINT `fk_model_catalog_0` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型能力目录';
