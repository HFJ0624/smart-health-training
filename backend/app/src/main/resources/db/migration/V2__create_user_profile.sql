-- =============================================================================
-- FitAgent P1 迁移 V2：user_profile（身体与训练背景，与用户 1:1）
-- 依赖 V1。
--
-- 注意本表没有体重列：体重唯一真相是 body_measurement，
-- 不另建一个容易不一致的"当前体重"字段（提示词硬性要求，与设计包一致）。
-- =============================================================================

CREATE TABLE `user_profile` (
  `id`                             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`                        BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `birth_date`                     DATE NULL COMMENT '可选出生日期；不提供时年龄相关公式不执行',
  `sex_for_equation`               ENUM('MALE','FEMALE','UNSPECIFIED') NOT NULL DEFAULT 'UNSPECIFIED' COMMENT '公式所需生理性别参数；未指定则不套用性别公式',
  `height_cm`                      DECIMAL(6,2) NULL COMMENT '身高 cm；本轮接口要求必填，数据库仍允许 NULL 以保持与设计包兼容',
  `training_level`                 ENUM('BEGINNER','INTERMEDIATE','ADVANCED') NOT NULL DEFAULT 'BEGINNER' COMMENT '自报训练经验',
  `activity_category`              ENUM('LIGHT','MODERATE','VIGOROUS') NULL COMMENT '已确认的长期活动类别；本轮不开放写入',
  `activity_confirmed_at`          DATETIME(3) NULL COMMENT '长期活动类别最近确认时间',
  `diet_preferences`               JSON NULL COMMENT '饮食偏好及用户主动提供的限制；本轮无消费方，不开放写入',
  `equipment`                      JSON NULL COMMENT '可用器械代码数组',
  `training_constraints`           JSON NULL COMMENT '可用时间与用户主动提供的训练限制',
  `professional_guidance_required` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户自报需专业指导；不保存诊断推断；本轮不开放写入',
  `lock_version`                   INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`                     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`                     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- (id,user_id) 复合唯一键：供子表做"父资源归属"复合外键，越权ID在数据库层就无法匹配
  UNIQUE KEY `uq_user_profile_1` (`id`, `user_id`),
  UNIQUE KEY `uq_user_profile_3` (`user_id`),
  KEY `idx_user_profile_2` (`user_id`),
  -- 只校验正数与精度，不引入未经说明的医学阈值
  CONSTRAINT `ck_user_profile_0` CHECK (`height_cm` IS NULL OR `height_cm` > 0),
  CONSTRAINT `fk_user_profile_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='身体与训练背景';
