-- =============================================================================
-- FitAgent P1 迁移 V3：user_goal（目标历史）
-- 依赖 V1。
--
-- active_user_id 是数据库派生的 STORED 生成列，配合 UNIQUE 实现"每用户最多一个 ACTIVE 目标"：
-- 列值就是 user_id，不同用户写入不同值互不冲突，因此它是"每用户一个"而不是"全局一个"；
-- NULL（DRAFT/ARCHIVED）不参与唯一性判定。ORM 不得在 INSERT/UPDATE 中携带该列。
-- =============================================================================

CREATE TABLE `user_goal` (
  `id`                       BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`                  BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `goal_type`                ENUM('FAT_LOSS','MUSCLE_GAIN','MAINTAIN','GENERAL_FITNESS') NOT NULL COMMENT '目标类型',
  `target_weight_kg`         DECIMAL(6,2) NULL COMMENT '用户目标体重 kg；NULL 表示未设定',
  `daily_intake_target_kcal` DECIMAL(9,2) NULL COMMENT '用户确认的饮食目标 kcal；列保留但本轮页面与接口不暴露',
  `weekly_sessions_target`   TINYINT UNSIGNED NULL COMMENT '每周目标训练次数',
  `starts_on`                DATE NOT NULL COMMENT '目标开始日期（用户时区业务日期）',
  `ends_on`                  DATE NULL COMMENT '可选结束日期',
  `status`                   ENUM('DRAFT','ACTIVE','ARCHIVED') NOT NULL DEFAULT 'DRAFT' COMMENT '目标状态',
  `active_user_id`           BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `user_id` ELSE NULL END) STORED COMMENT '数据库派生；ORM 不写该列',
  `lock_version`             INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_goal_1` (`id`, `user_id`),
  KEY `idx_user_goal_2` (`user_id`),
  UNIQUE KEY `uq_user_goal_3` (`active_user_id`),
  CONSTRAINT `ck_user_goal_0` CHECK (`target_weight_kg` IS NULL OR `target_weight_kg` > 0),
  CONSTRAINT `ck_user_goal_1` CHECK (`daily_intake_target_kcal` IS NULL OR `daily_intake_target_kcal` > 0),
  CONSTRAINT `ck_user_goal_2` CHECK (`ends_on` IS NULL OR `ends_on` >= `starts_on`),
  CONSTRAINT `fk_user_goal_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='目标历史';
