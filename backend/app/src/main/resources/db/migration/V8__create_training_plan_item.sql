-- =============================================================================
-- FitAgent P1 迁移 V8：training_plan_item（计划日内训练动作）
-- 依赖 V7、V5、V1。
--
-- 字段校验矩阵（docs/p1-plan-import.md §5）在 Service 层按 category 分别执行：
--   STRENGTH 必填 target_sets，禁 duration；CARDIO 必填 duration，禁 sets/reps/load/rest；
--   MOBILITY 至少给一项训练量，禁 load。
-- CHECK 只能表达局部约束（如 reps_max >= reps_min、正数），跨表与按类型的规则数据库做不到。
--
-- exercise_id 外键保证不可能塞入字典外的动作；但外键只保证"存在"，
-- status='ACTIVE' 的有效性由 Service 校验（EXERCISE_UNAVAILABLE）。
-- =============================================================================

CREATE TABLE `training_plan_item` (
  `id`                        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`                   BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_day_id`               BIGINT UNSIGNED NOT NULL COMMENT '所属计划日',
  `exercise_id`               BIGINT UNSIGNED NOT NULL COMMENT '动作字典 ID',
  `position_no`               SMALLINT UNSIGNED NOT NULL COMMENT '动作顺序；服务端按数组顺序规范化为 1..n',
  `target_sets`               SMALLINT UNSIGNED NULL COMMENT '力量训练目标组数',
  `reps_min`                  SMALLINT UNSIGNED NULL COMMENT '目标次数下界',
  `reps_max`                  SMALLINT UNSIGNED NULL COMMENT '目标次数上界',
  `target_external_load_kg`   DECIMAL(8,2) NULL COMMENT '目标外部负重 kg；0 表示自重，NULL 表示未设定',
  `target_duration_seconds`   INT UNSIGNED NULL COMMENT '时长型训练目标秒数',
  `rest_seconds`              SMALLINT UNSIGNED NULL COMMENT '计划组间休息秒数',
  `notes`                     VARCHAR(800) NULL COMMENT '动作说明',
  `created_at`                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`                DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_training_plan_item_1` (`id`, `user_id`),
  KEY `idx_training_plan_item_2` (`user_id`),
  UNIQUE KEY `uq_training_plan_item_3` (`plan_day_id`, `position_no`),
  CONSTRAINT `ck_training_plan_item_0` CHECK (`target_sets` IS NULL OR `target_sets` > 0),
  CONSTRAINT `ck_training_plan_item_1` CHECK (`reps_min` IS NULL OR `reps_min` > 0),
  CONSTRAINT `ck_training_plan_item_2` CHECK (`reps_max` IS NULL OR `reps_max` >= `reps_min`),
  CONSTRAINT `ck_training_plan_item_3` CHECK (`target_external_load_kg` IS NULL OR `target_external_load_kg` >= 0),
  CONSTRAINT `ck_training_plan_item_4` CHECK (`target_duration_seconds` IS NULL OR `target_duration_seconds` > 0),
  CONSTRAINT `fk_training_plan_item_0` FOREIGN KEY (`plan_day_id`, `user_id`) REFERENCES `training_plan_day` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_item_1` FOREIGN KEY (`exercise_id`) REFERENCES `exercise_reference` (`id`),
  CONSTRAINT `fk_training_plan_item_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划日内训练动作';
