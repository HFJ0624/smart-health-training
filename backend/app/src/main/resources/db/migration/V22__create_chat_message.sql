-- =============================================================================
-- FitAgent agent-v1 迁移 V22：chat_message（会话消息）
-- 依赖 V18、V19。对应设计 §7 的 chat_message、§8 的顺序与历史口径、§11.1 第 5 条的载荷形状。
--
-- 顺序：UNIQUE(conversation_id, message_no)。message_no 由应用**在会话行锁下**原子分配
-- （SELECT ... FOR UPDATE 会话行后 max+1，设计 §8），本表用唯一索引把它变成数据库事实——
-- 并发两个问题不会交错出 1,3,2 这种序列，也不会出现同号两条消息。
--
-- 历史回放只取 role ∈ (user, assistant) 的最终可见正文（§8）：
--   tool / system 两类取值保留在列定义里是为了如实记录协议层消息种类，
--   但 CoachPromptHistoryLoader 只读前两值，绝不把历史原始 tool 消息塞进新请求
--   ——那样会把上一轮的工具结果当成本轮的事实来源，而数据可能已经变了。
--
-- 结构化列（tool_calls / citations / fact_cards / missing_fields）都是 JSON：
-- 值与来源只可能由工具结果组装（§9：个人数值不由模型填写），因此 fact_cards 是**服务端写的**，
-- 前端展示历史消息时不需要重跑任何计算。缺数据用 missing_fields 显式列出，不以 0 代替未知。
--
-- data_revision 记该条消息的事实快照读自哪个修订号；is_stale 是 run 结束时的陈旧判定结果，
-- 真为 true 时正文与卡片保留原快照与原日期，不自动付费重跑（§9）。
--
-- status 只有两个取值：COMPLETE = 该 run 正常拿到可用回答；
-- INCOMPLETE = 该 run 以停止/失败收尾，正文是"已核实事实 + 停止说明"的确定性模板（§4 第 13 步）。
-- 两者都必须如实显示，不拿 INCOMPLETE 冒充 COMPLETE。
-- =============================================================================

CREATE TABLE `chat_message` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属用户；与会话同属，查询双条件限定',
  `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT '所属会话；FK → chat_conversation(id, user_id)',
  `run_id`          BIGINT UNSIGNED NULL COMMENT '产生该消息的 run；FK → agent_run(id, user_id)；用户消息在提交时即绑定，system 消息为 NULL',
  `message_no`      INT UNSIGNED NOT NULL COMMENT '会话内单调序号（会话行锁下原子分配）；前端按它升序渲染',
  `role`            ENUM('user','assistant','tool','system') NOT NULL COMMENT '消息角色，**小写字面量**（§11.1 第 5 条：前端按 role:user/assistant 匹配）',
  `content_text`    TEXT NULL COMMENT '可见正文；工具消息的结构化载荷在 tool_calls/citations 列，不重复塞进正文',
  `tool_call_id`    CHAR(64) NULL COMMENT 'role=tool 时对应的 tool_call_id；其余为 NULL',
  `tool_calls`      JSON NULL COMMENT 'assistant 消息声明的工具调用（原样保留以便协议配对追溯）；无则为 NULL',
  `citations`       JSON NULL COMMENT '本条回答引用且**已通过存在性校验**的证据 ID；被丢弃的引用计入 missing_fields',
  `fact_cards`      JSON NULL COMMENT '服务端按引用从工具结果取值的完整事实卡片（§11.1 第 5 条的 factCards）',
  `missing_fields`  JSON NULL COMMENT '已知缺失项清单（§9：不以 0 代替未知）；无缺失为空数组',
  `data_revision`   BIGINT UNSIGNED NULL COMMENT '本条消息事实快照读自的 user_account.data_revision',
  `is_stale`        BOOLEAN NOT NULL DEFAULT FALSE COMMENT 'run 结束时修订号与开始时不同 → 真；保留原快照不重跑',
  `status`          ENUM('COMPLETE','INCOMPLETE') NOT NULL DEFAULT 'COMPLETE' COMMENT '正文完整性：正常回答 / 停止或失败收尾的确定性模板',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 会话内顺序的唯一性由数据库保证，不依赖应用"记得加锁"
  UNIQUE KEY `uq_chat_message_1` (`conversation_id`, `message_no`),
  UNIQUE KEY `uq_chat_message_2` (`id`, `user_id`),
  -- 增量拉取（after=message_no）与历史回放读取形态
  KEY `idx_chat_message_3` (`user_id`, `conversation_id`, `message_no`),
  CONSTRAINT `ck_chat_message_0` CHECK (`message_no` > 0),
  CONSTRAINT `ck_chat_message_1` CHECK (`role` <> 'tool' OR `tool_call_id` IS NOT NULL),
  CONSTRAINT `fk_chat_message_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_chat_message_1` FOREIGN KEY (`conversation_id`, `user_id`) REFERENCES `chat_conversation` (`id`, `user_id`),
  CONSTRAINT `fk_chat_message_2` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='教练会话消息';
