-- =============================================================================
-- FitAgent agent-v1 迁移 V18：chat_conversation（教练会话头）
-- 依赖 V1。对应设计 §7 的 chat_conversation 与 §11 的会话接口。
--
-- 版本号说明：设计 §7 的排期把 chat_conversation 记作 V17，但 V17 已被 budget_reservation 占用
-- （见该文件末尾的版本号说明），本表起从 V18 顺延，不要再新建第二个 V17。
--
-- 归属手法沿用 P1：UNIQUE(id, user_id) 是给子表做复合外键的目标，
-- 于是"消息/ run 属于某会话"与"该会话属于当前用户"在一次 JOIN 里同时被数据库证明，
-- 不依赖应用记得加 WHERE user_id = ?（AGENTS.md：越权与不存在必须同形）。
-- UNIQUE(user_id, id) 与 KEY(user_id, last_message_at) 是"本人会话列表按最近活跃排序"的读取形态。
--
-- title 可空：会话先建、标题由服务端从首个问题截取（§11.1 请求体是 {}），
-- 空标题不是"未命名"的占位文案，前端自行显示，服务端不猜语义。
-- last_message_at 由消息分配序号的同一路径写入，不由客户端提供。
-- =============================================================================

CREATE TABLE `chat_conversation` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得，永不出现在请求参数里',
  `title`           VARCHAR(120) NULL COMMENT '会话标题；由服务端从首个问题截取，未提问时为 NULL',
  `status`          ENUM('ACTIVE','ARCHIVED') NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态；本轮只有进行中与已归档两种',
  `last_message_at` DATETIME(3) NULL COMMENT 'UTC 最后一条消息时刻；列表排序用，未发消息时为 NULL',
  `lock_version`    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 子表（agent_run / chat_message）复合外键的目标
  UNIQUE KEY `uq_chat_conversation_1` (`id`, `user_id`),
  -- 本人会话定位：跨用户读取在索引层面就走不到别人的行
  UNIQUE KEY `uq_chat_conversation_2` (`user_id`, `id`),
  -- 会话列表按"最近活跃"倒序；NULL 排在末尾由查询侧决定，不在索引里排序规则化
  KEY `idx_chat_conversation_3` (`user_id`, `last_message_at`),
  CONSTRAINT `fk_chat_conversation_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='教练会话头';
