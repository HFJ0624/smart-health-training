-- =============================================================================
-- FitAgent 阶段 2 迁移 V25：workout_session_set（逐组记录）
-- 依赖 V24。
--
-- 一组一行，`is_completed` 是"用户确认这一组做了"的唯一依据：
-- 默认 0，**服务层绝不替用户勾**。统计里的"已确认完成组数"就是 is_completed=1 的行数。
--
-- NULL 与 0 的区分是这一轮最容易做错的地方：
--   · reps = NULL → 这一组没记次数（未知），不是"做了 0 次"；
--   · external_load_kg = NULL → 未设定负重，**不自动填成 0**（自重是用户显式填 0 才有意义）；
--   · duration_seconds = NULL → 未记录时长。
-- 允许 0 的列（reps）用 >= 0，只允许正数的列（时长）用 > 0。
-- =============================================================================

CREATE TABLE `workout_session_set` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属用户；冗余存放以便与 entry 做复合外键',
  `entry_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属动作条目',
  `set_no`           SMALLINT UNSIGNED NOT NULL COMMENT '组序号，从 1 开始；同一 entry 内唯一',
  `reps`             SMALLINT UNSIGNED NULL COMMENT '实际次数；NULL=未记录，0 是真实已知值（例如空组）',
  `external_load_kg` DECIMAL(6,2) NULL COMMENT '实际外部负重 kg；NULL=未设定，0=自重（用户显式填写）',
  `duration_seconds` INT UNSIGNED NULL COMMENT '该组实际时长秒；NULL=未记录',
  `is_completed`     TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户是否确认这一组已完成；默认 0，不替用户确认',
  `notes`            VARCHAR(200) NULL COMMENT '该组备注',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_session_set_1` (`id`, `user_id`),
  KEY `idx_workout_session_set_2` (`entry_id`),
  UNIQUE KEY `uq_workout_session_set_3` (`entry_id`, `set_no`),
  CONSTRAINT `ck_workout_session_set_0` CHECK (`set_no` > 0),
  CONSTRAINT `ck_workout_session_set_1` CHECK (`reps` IS NULL OR `reps` >= 0),
  CONSTRAINT `ck_workout_session_set_2` CHECK (`external_load_kg` IS NULL OR `external_load_kg` >= 0),
  CONSTRAINT `ck_workout_session_set_3` CHECK (`duration_seconds` IS NULL OR `duration_seconds` > 0),
  CONSTRAINT `ck_workout_session_set_4` CHECK (`is_completed` IN (0, 1)),
  CONSTRAINT `fk_workout_session_set_0` FOREIGN KEY (`entry_id`, `user_id`) REFERENCES `workout_session_entry` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='训练逐组记录';
