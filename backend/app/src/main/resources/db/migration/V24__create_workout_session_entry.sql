-- =============================================================================
-- FitAgent 阶段 2 迁移 V24：workout_session_entry（场次内的动作条目）
-- 依赖 V23、V5（exercise_reference）、V8（training_plan_item）。
--
-- 一条 entry 对应"这个场次里做的一个动作"。plan_item_id 指向参考计划条目：
--   · UNIQUE(session_id, plan_item_id) → 同一个参考条目不会被拆成两行重复计数；
--   · UNIQUE(session_id, plan_item_id) 允许 NULL（MySQL 唯一索引不约束 NULL），
--     所以自主添加的动作可以有任意多条。
-- 计划值永远只是参考：实际做了哪些组由 set 表逐组表达，缺失就是 NULL，不用计划值补成实际值。
-- duration_seconds 是"该动作的实际时长"，与场次历时（ended_at - started_at）是两回事，不相加。
-- =============================================================================

CREATE TABLE `workout_session_entry` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '所属用户；冗余存放以便与 session 做复合外键',
  `session_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属场次',
  `exercise_id`        BIGINT UNSIGNED NOT NULL COMMENT '动作字典 ID',
  `plan_item_id`       BIGINT UNSIGNED NULL COMMENT '参考的计划条目；NULL=自主动作',
  `position_no`        SMALLINT UNSIGNED NOT NULL COMMENT '场次内顺序，从 1 开始',
  `target_duration_seconds` INT UNSIGNED NULL COMMENT '该动作实际时长（秒）；NULL=未记录，不用计划时长补',
  `distance_meters`    DECIMAL(10,2) NULL COMMENT '可选距离 m；仅用户填写才有值',
  `notes`              VARCHAR(500) NULL COMMENT '用户备注',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_session_entry_1` (`id`, `user_id`),
  KEY `idx_workout_session_entry_2` (`session_id`),
  UNIQUE KEY `uq_workout_session_entry_3` (`session_id`, `position_no`),
  UNIQUE KEY `uq_workout_session_entry_4` (`session_id`, `plan_item_id`),
  CONSTRAINT `ck_workout_session_entry_0` CHECK (`position_no` > 0),
  CONSTRAINT `ck_workout_session_entry_1` CHECK (`target_duration_seconds` IS NULL OR `target_duration_seconds` > 0),
  CONSTRAINT `ck_workout_session_entry_2` CHECK (`distance_meters` IS NULL OR `distance_meters` > 0),
  CONSTRAINT `fk_workout_session_entry_0` FOREIGN KEY (`session_id`, `user_id`) REFERENCES `workout_session` (`id`, `user_id`),
  CONSTRAINT `fk_workout_session_entry_1` FOREIGN KEY (`exercise_id`) REFERENCES `exercise_reference` (`id`),
  CONSTRAINT `fk_workout_session_entry_2` FOREIGN KEY (`plan_item_id`, `user_id`) REFERENCES `training_plan_item` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='训练场次动作条目';
