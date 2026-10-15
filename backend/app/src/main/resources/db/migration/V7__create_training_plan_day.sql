-- =============================================================================
-- FitAgent P1 迁移 V7：training_plan_day（每周计划日模板）
-- 依赖 V6、V1。
--
-- UNIQUE (plan_id, iso_weekday) 是"每个星期最多一个计划日"的数据库级保证。
-- plan_id 指向具体的计划版本（而不是计划组），所以历史版本的某一天的内容永不被后续草稿改写。
--
-- 复合外键 (plan_id, user_id) -> training_plan (id, user_id) 让"拿着别人的 plan_id 插自己的 user_id"
-- 在数据库层就无法匹配，而不只是依赖应用检查。
-- =============================================================================

CREATE TABLE `training_plan_day` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`     BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_id`     BIGINT UNSIGNED NOT NULL COMMENT '明确的计划版本 ID',
  `iso_weekday` TINYINT UNSIGNED NOT NULL COMMENT 'ISO 星期：1 周一 … 7 周日',
  `title`       VARCHAR(120) NULL COMMENT '当日主题',
  `is_rest_day` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户计划中的休息日；休息日不得配置动作（跨表规则由 Service 校验）',
  `notes`       VARCHAR(800) NULL COMMENT '说明',
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_training_plan_day_1` (`id`, `user_id`),
  KEY `idx_training_plan_day_2` (`user_id`),
  UNIQUE KEY `uq_training_plan_day_3` (`plan_id`, `iso_weekday`),
  CONSTRAINT `ck_training_plan_day_0` CHECK (`iso_weekday` >= 1 AND `iso_weekday` <= 7),
  CONSTRAINT `fk_training_plan_day_0` FOREIGN KEY (`plan_id`, `user_id`) REFERENCES `training_plan` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_day_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每周计划日模板';
