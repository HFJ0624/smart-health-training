-- =============================================================================
-- FitAgent agent-v1 迁移 V21：agent_step（run 的脱敏操作轨迹）
-- 依赖 V19、V20。对应设计 §7 的 agent_step 与 §11.1 规范化决定第 6 条。
--
-- 这张表**不存**任何东西足以还原模型隐藏推理或密钥材料，且刻意向这个方向收窄：
--   1. 没有 reasoning_content 列——本轮供应商返回的思考文本不落地、不外发（§11.1 第 6 条）；
--   2. `arguments_redacted` 只存 TraceRedaction 处理过的参数骨架：值里的凭据形态已被替换，
--      用户自由文本不进这里（问题原文已经在 chat_message 里，一处存就够）；
--   3. `result_summary` 是"几个来源、是否完整"这类工程摘要，**不是**工具返回的原始明细，
--      更不是回答正文——完整结果只存 agent_run.result_payload，页面绝不拿这里的截断文本冒充答案。
--
-- 轨迹是**可丢的诊断材料，不是计费依据**（设计 §10）：
-- 写这里的失败绝不触发模型调用重发，因此本表与 model_call 之间没有任何外键强绑定的写入顺序要求，
-- model_call_id 只是"这一步对应哪次远程调用"的可选关联。
--
-- UNIQUE(run_id, step_no)：step_no 由 TraceRecorder 在 run 内单调分配（从 1 起），
-- 重放同一个 run 时撞键即丢弃，不会产生第二条同序号轨迹。
-- =============================================================================

CREATE TABLE `agent_step` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '所属用户；与 run 同属，查询双条件限定',
  `run_id`             BIGINT UNSIGNED NOT NULL COMMENT '所属 run；FK → agent_run(id, user_id)',
  `step_no`            INT UNSIGNED NOT NULL COMMENT 'run 内单调步骤号（从 1 起，由 TraceRecorder 分配）',
  `model_call_id`      BIGINT UNSIGNED NULL COMMENT '关联的远程调用；FK → model_call(id, user_id)；工具步骤为 NULL',
  `event_type`         VARCHAR(32) NOT NULL COMMENT '事件类型字面值（MODEL_CALL/TOOL_CALL/TOOL_RESULT/CONTEXT_TRUNCATED/CITATION_REJECTED/ANSWER/RUN_STOPPED）',
  `tool_name`          VARCHAR(128) NULL COMMENT '工具名；非工具步骤为 NULL',
  `tool_call_id`       VARCHAR(128) NULL COMMENT '供应商给出的 tool_call_id 原值（协议配对排查用，不含个人数据）',
  `arguments_redacted` JSON NULL COMMENT '脱敏后的参数骨架；不含凭据形态的值，也不含用户自由文本',
  `result_summary`     VARCHAR(500) NULL COMMENT '工程摘要（几个来源/是否完整/调用次数）；**不是**回答正文，界面与恢复都不依赖它',
  `status`             VARCHAR(16) NOT NULL COMMENT '步骤状态：SUCCEEDED / FAILED / DECLARED（仅声明未执行）；界面按它上色，不解析 summary',
  `duration_ms`        BIGINT UNSIGNED NULL COMMENT '该步耗时毫秒；未执行为 NULL（不是 0）',
  `error_code`         VARCHAR(64) NULL COMMENT '稳定机器码（工具错误码或停止原因）；无错误为 NULL',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_agent_step_1` (`run_id`, `step_no`),
  UNIQUE KEY `uq_agent_step_2` (`id`, `user_id`),
  -- 进度摘要读取形态：一个 run 的全部步骤（按 step_no 升序）
  KEY `idx_agent_step_3` (`user_id`, `run_id`, `step_no`),
  CONSTRAINT `ck_agent_step_0` CHECK (`step_no` > 0),
  CONSTRAINT `fk_agent_step_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_agent_step_1` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_agent_step_2` FOREIGN KEY (`model_call_id`, `user_id`) REFERENCES `model_call` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='run 的脱敏操作轨迹（不含隐藏推理与密钥材料）';
