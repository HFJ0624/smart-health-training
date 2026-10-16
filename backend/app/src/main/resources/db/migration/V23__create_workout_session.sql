-- =============================================================================
-- FitAgent 阶段 2 迁移 V23：workout_session（实际训练场次）
-- 依赖 V1（user_account）与 V6/V7（training_plan / training_plan_day）。
--
-- 三条数据库级闸门：
--   1. (user_id, client_record_uuid) 唯一 → 「开始」重复提交不多建场次；
--   2. active_user_id（STORED 生成列）+ UNIQUE → 每用户至多一个进行中草稿，
--      完成或丢弃后该列为 NULL，不再占用闸门，因此历史记录不受限制；ORM 不得写入该列；
--   3. plan_id / plan_day_id 成对出现，且外键 RESTRICT —— 计划子项只有在草稿态才会被
--      TrainingPlanService.update() 删除重建，而服务层禁止引用草稿版本，所以被引用的行永不消失。
--
-- 时区口径：timezone 是「开始时快照的账户时区」，之后账户改时区不回写历史记录；
-- started_at / ended_at 存 UTC，business_date 由服务端按该时区推导，客户端不传。
-- 跨午夜结束不把业务日期挪到第二天。
-- =============================================================================

CREATE TABLE `workout_session` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `client_record_uuid` CHAR(36) NOT NULL COMMENT '客户端去重 UUID v4；服务端规范化小写后入库',
  `business_date`      DATE NOT NULL COMMENT '本场次的业务日期（按 timezone 推导）；统计按它分组',
  `timezone`           VARCHAR(64) NOT NULL COMMENT '开始时快照的 IANA 时区；不随账户后续变更',
  `plan_id`            BIGINT UNSIGNED NULL COMMENT '参考的计划版本；NULL=自主记录',
  `plan_day_id`        BIGINT UNSIGNED NULL COMMENT '参考的计划日；与 plan_id 成对出现',
  `plan_revision_no`   INT UNSIGNED NULL COMMENT '开始时的计划修订号快照，用于"按原版本回查"',
  `started_at`         DATETIME(3) NOT NULL COMMENT 'UTC 开始时间',
  `ended_at`           DATETIME(3) NULL COMMENT 'UTC 结束时间；仅在用户确认结束时写入',
  `status`             ENUM('DRAFT','COMPLETED','DISCARDED') NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT=进行中草稿不计正式统计；COMPLETED=用户已确认结束；DISCARDED=丢弃',
  `active_user_id`     BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 'DRAFT' THEN `user_id` ELSE NULL END) STORED COMMENT '数据库派生的单草稿闸门；ORM 不写该列',
  `notes`              VARCHAR(500) NULL COMMENT '用户备注',
  `lock_version`       INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '聚合级乐观锁版本；保存与结束都携带',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_session_1` (`id`, `user_id`),
  KEY `idx_workout_session_2` (`user_id`),
  UNIQUE KEY `uq_workout_session_3` (`user_id`, `client_record_uuid`),
  UNIQUE KEY `uq_workout_session_4` (`active_user_id`),
  -- 覆盖"某人某天的已确认记录"与历史分页
  KEY `idx_workout_session_5` (`user_id`, `business_date`, `status`),
  CONSTRAINT `ck_workout_session_0` CHECK (`plan_id` IS NULL OR `plan_day_id` IS NOT NULL),
  CONSTRAINT `ck_workout_session_1` CHECK (`plan_day_id` IS NULL OR `plan_id` IS NOT NULL),
  CONSTRAINT `ck_workout_session_2` CHECK (`plan_revision_no` IS NULL OR `plan_revision_no` > 0),
  CONSTRAINT `ck_workout_session_3` CHECK (`ended_at` IS NULL OR `ended_at` >= `started_at`),
  -- 「确认结束必须有结束时间」「草稿不可能有结束时间」两条分开写：
  -- DISCARDED 既可能来自没结束过的草稿（ended_at 为 NULL），也可能是完成后作废，不能混为一谈。
  CONSTRAINT `ck_workout_session_4` CHECK (`status` <> 'COMPLETED' OR `ended_at` IS NOT NULL),
  CONSTRAINT `ck_workout_session_5` CHECK (`status` <> 'DRAFT' OR `ended_at` IS NULL),
  CONSTRAINT `fk_workout_session_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_workout_session_1` FOREIGN KEY (`plan_id`, `user_id`) REFERENCES `training_plan` (`id`, `user_id`),
  CONSTRAINT `fk_workout_session_2` FOREIGN KEY (`plan_day_id`, `user_id`) REFERENCES `training_plan_day` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际训练场次';
