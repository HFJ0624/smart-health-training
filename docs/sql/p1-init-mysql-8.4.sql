-- =============================================================================
-- FitAgent P1 基础功能 · MySQL 8.4 建表脚本（8 张表 + 动作字典种子）
--
-- 由 backend/app/src/main/resources/db/migration 下的 V1..V9 按版本号顺序拼接生成，
-- 内容与 Flyway 迁移逐字一致；两条路线只选一条：
--   A. 让应用启动时由 Flyway 自动迁移（推荐，fitagent 账号需 DDL 权限）；
--   B. 你手工执行本脚本建表，然后把 FITAGENT_FLYWAY_ENABLED 设为 false。
--
-- 要建的 8 张表：
--   1 user_account  2 user_profile  3 user_goal  4 body_measurement
--   5 exercise_reference  6 training_plan  7 training_plan_day  8 training_plan_item
--
-- 硬性前提：MySQL 8.0.16+（CHECK 约束才真正生效），建议 8.4。
--   5.7 上 utf8mb4_0900_ai_ci 不存在会直接报错，且 CHECK 被静默忽略，不要用 5.7 跑。
-- 本脚本不含 DROP / DELETE / TRUNCATE，不创建 processing_job / agent_run 等 P2/P3 表。
-- 重复执行会在已存在的表上报错，这是有意为之，避免半成品状态。
-- =============================================================================

CREATE DATABASE IF NOT EXISTS `smart_health_training` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `smart_health_training`;
SET NAMES utf8mb4;
SET time_zone = '+00:00';


-- -----------------------------------------------------------------------------
-- >>> V1__create_user_account.sql
-- -----------------------------------------------------------------------------
-- =============================================================================
-- FitAgent P1 迁移 V1：user_account（微信用户与数据修订号）
-- 目标 MySQL 8.4。不含任何 DROP/DELETE。
--
-- 排序规则要点（详见 docs/p1-database.md §5）：
--   表默认 utf8mb4_0900_ai_ci（大小写不敏感），但微信身份三列显式改为 utf8mb4_bin。
--   openid 由微信生成且大小写有意义，若按 CI 比较，两个不同用户可能被折叠成同一账号，
--   属于身份混淆级风险。这是本轮对设计包 schema.sql 唯一的定义级修改。
-- =============================================================================

CREATE TABLE `user_account` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `wechat_app_id`   VARCHAR(64)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '微信小程序 AppID；由后端配置注入，不接受前端传入',
  `wechat_open_id`  VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'code2Session 返回的 openid；大小写逐字符精确比较',
  `wechat_union_id` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '可选 unionid；未绑定开放平台时为 NULL',
  `nickname`        VARCHAR(80)  NULL COMMENT '用户显示名称；本轮仅允许用户自填',
  `role`            ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '应用角色；本轮只产生 USER',
  `status`          ENUM('ACTIVE','DISABLED','DELETION_PENDING') NOT NULL DEFAULT 'ACTIVE' COMMENT '账户状态；非 ACTIVE 时登录被拒绝',
  `timezone`        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '业务日期所用时区；服务端用 ZoneId 校验',
  `data_revision`   BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '档案/目标/体重/计划变更时在同事务内递增',
  `last_login_at`   DATETIME(3) NULL COMMENT 'UTC 最近登录时间',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 并发首次登录只注册一个账号的最终防线；不是提示性索引
  UNIQUE KEY `uq_user_account_1` (`wechat_app_id`, `wechat_open_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信用户与数据修订号';

-- -----------------------------------------------------------------------------
-- >>> V2__create_user_profile.sql
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- >>> V3__create_user_goal.sql
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- >>> V4__create_body_measurement.sql
-- -----------------------------------------------------------------------------
-- =============================================================================
-- FitAgent P1 迁移 V4：body_measurement（身体记录）
-- 依赖 V1。
--
-- "最新体重"的查询口径固定为 ORDER BY measured_at DESC, id DESC LIMIT 1，
-- 因此补录一条业务日期更早的记录不会成为最新体重（验收 A7）。
-- (user_id, client_record_uuid) 唯一索引是"重复提交不重复新增"的数据库防线；
-- 该列保持表默认 CI 排序规则，服务端写入前把 UUID 规范化为小写，两者叠加保证去重。
-- =============================================================================

CREATE TABLE `body_measurement` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `measured_at`       DATETIME(3) NOT NULL COMMENT 'UTC 实际测量时间；判定最新的依据',
  `business_date`     DATE NOT NULL COMMENT '按用户时区从 measured_at 推导的业务日期；服务端计算，客户端不传',
  `weight_kg`         DECIMAL(6,2) NOT NULL COMMENT '用户实测体重 kg',
  `waist_cm`          DECIMAL(6,2) NULL COMMENT '可选腰围 cm',
  `body_fat_percent`  DECIMAL(5,2) NULL COMMENT '可选自报体脂率；不通过照片推算',
  `source`            ENUM('MANUAL','IMPORT') NOT NULL DEFAULT 'MANUAL' COMMENT '记录来源；本轮恒为 MANUAL',
  `client_record_uuid` CHAR(36) NOT NULL COMMENT '客户端去重 UUID v4；服务端规范化小写后入库',
  `notes`             VARCHAR(500) NULL COMMENT '备注',
  `lock_version`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_body_measurement_1` (`id`, `user_id`),
  KEY `idx_body_measurement_2` (`user_id`),
  UNIQUE KEY `uq_body_measurement_3` (`user_id`, `client_record_uuid`),
  -- 覆盖"按业务日期区间 + 测量时间排序"的历史查询与最新一条查询
  KEY `idx_body_measurement_4` (`user_id`, `business_date`, `measured_at`),
  CONSTRAINT `ck_body_measurement_0` CHECK (`weight_kg` > 0),
  CONSTRAINT `ck_body_measurement_1` CHECK (`waist_cm` IS NULL OR `waist_cm` > 0),
  CONSTRAINT `ck_body_measurement_2` CHECK (`body_fat_percent` IS NULL OR (`body_fat_percent` >= 0 AND `body_fat_percent` <= 100)),
  CONSTRAINT `fk_body_measurement_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='身体记录';

-- -----------------------------------------------------------------------------
-- >>> V5__create_exercise_reference.sql
-- -----------------------------------------------------------------------------
-- =============================================================================
-- FitAgent P1 迁移 V5：exercise_reference（训练动作字典）
-- 无外键依赖。本轮只读：页面从 status='ACTIVE' 的条目中选择，用户不能增删改字典。
--
-- exercise_code 保持表默认 CI 排序规则 + 服务端大写规范化：
-- 导入文件里写 barbell_squat 也能命中 BARBELL_SQUAT，但命中不了就是错误，绝不做近似匹配。
-- 25 条种子在 V9 写入；schema.sql 原本没有该表的种子，本轮必须自备。
-- =============================================================================

CREATE TABLE `exercise_reference` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `exercise_code` VARCHAR(64) NOT NULL COMMENT '动作唯一代码；大写下划线，导入匹配键',
  `name`          VARCHAR(120) NOT NULL COMMENT '动作名称',
  `category`      ENUM('STRENGTH','CARDIO','MOBILITY') NOT NULL COMMENT '动作类型；决定条目字段校验矩阵',
  `muscle_groups` JSON NULL COMMENT '相关肌群数组',
  `equipment`     JSON NULL COMMENT '所需器械代码数组',
  `instructions`  TEXT NULL COMMENT '经维护的基础操作说明；本轮全部留空，不引入未经来源核对的文本',
  `source_url`    VARCHAR(1000) NULL COMMENT '说明来源；本轮全部留空，不编造权威链接',
  `status`        ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_exercise_reference_1` (`exercise_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='训练动作字典';

-- -----------------------------------------------------------------------------
-- >>> V6__create_training_plan.sql
-- -----------------------------------------------------------------------------
-- =============================================================================
-- FitAgent P1 迁移 V6：training_plan（训练计划不可覆盖的版本）
-- 依赖 V1。
--
-- 【本阶段与设计包 schema.sql 的差异】
--   不创建 source_job_id / source_run_id 两列，也不创建它们指向
--   processing_job(P3) / agent_run(P2) 的两个复合外键。
--   否则本轮就被迫连带建两张 AI 表。加回方式见 docs/p1-database.md §4.2（后续新增迁移）。
--   保留：基础表名、核心字段、plan_group_uuid + revision_no 版本设计、
--         active_user_id 的"每用户最多一个 ACTIVE"、(id,user_id) 归属复合键、lock_version 乐观锁。
--
-- source 枚举保留 'AI' 取值但本轮没有任何代码路径会写入它，
-- 这样后续接入 Agent 时不需要改枚举、不需要动已有数据。
-- =============================================================================

CREATE TABLE `training_plan` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_group_uuid` CHAR(36) NOT NULL COMMENT '同一计划各版本共享的逻辑 ID；服务端生成小写 UUID，客户端不可指定',
  `revision_no`     INT UNSIGNED NOT NULL COMMENT '该计划版本号；服务端取组内 max+1',
  `base_plan_id`    BIGINT UNSIGNED NULL COMMENT '修改基于的旧版本；NULL 表示首版',
  `title`           VARCHAR(160) NOT NULL COMMENT '计划名称',
  `source`          ENUM('MANUAL','IMPORT','AI') NOT NULL COMMENT '来源；本轮只产生 MANUAL 与 IMPORT',
  `status`          ENUM('DRAFT','ACTIVE','ARCHIVED','DISCARDED') NOT NULL DEFAULT 'DRAFT' COMMENT '先存草稿，用户确认后激活；丢弃只改状态不删行',
  `goal_snapshot`   JSON NOT NULL COMMENT '计划建立时的目标/器械/限制快照；服务端生成，形状带 schema_version，校验体积',
  `starts_on`       DATE NOT NULL COMMENT '计划生效开始日期',
  `ends_on`         DATE NULL COMMENT '可选结束日期',
  `confirmed_at`    DATETIME(3) NULL COMMENT '用户确认生效的 UTC 时间',
  `active_user_id`  BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `user_id` ELSE NULL END) STORED COMMENT '数据库派生；每用户最多一个 ACTIVE 计划；ORM 不写该列',
  `lock_version`    INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_training_plan_1` (`id`, `user_id`),
  KEY `idx_training_plan_2` (`user_id`),
  UNIQUE KEY `uq_training_plan_3` (`user_id`, `plan_group_uuid`, `revision_no`),
  UNIQUE KEY `uq_training_plan_4` (`active_user_id`),
  CONSTRAINT `ck_training_plan_0` CHECK (`revision_no` > 0),
  CONSTRAINT `ck_training_plan_1` CHECK (`ends_on` IS NULL OR `ends_on` >= `starts_on`),
  CONSTRAINT `fk_training_plan_0` FOREIGN KEY (`base_plan_id`, `user_id`) REFERENCES `training_plan` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_3` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='训练计划不可覆盖的版本';

-- -----------------------------------------------------------------------------
-- >>> V7__create_training_plan_day.sql
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- >>> V8__create_training_plan_item.sql
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- >>> V9__seed_exercise_reference.sql
-- -----------------------------------------------------------------------------
-- =============================================================================
-- FitAgent P1 迁移 V9：动作字典种子（25 条）
-- 依赖 V5。设计包 schema.sql 没有 exercise_reference 的种子，本轮必须自备，
-- 否则页面无可选项、导入模板也无法"真实可导入"。
--
-- 两条刻意的取舍：
--   1) source_url 与 instructions 全部留 NULL。不引入未经来源核对的动作说明文本，
--      也不编造权威链接。是否补录由维护者审核后决定。
--   2) 不显式指定 id，交给自增分配，避免占位 ID 与后续场景冲突。
--      重复执行由 Flyway 历史表保证一次性；手工执行本文件前请先确认字典为空。
--
-- category 决定导入/编辑时的字段校验矩阵（见 docs/p1-plan-import.md §5），不是展示用标签。
-- =============================================================================

INSERT INTO `exercise_reference` (`exercise_code`, `name`, `category`, `muscle_groups`, `equipment`, `status`) VALUES
-- 力量 13 条
('BARBELL_BACK_SQUAT',        '杠铃深蹲',     'STRENGTH', JSON_ARRAY('股四头肌','臀大肌','腘绳肌'),      JSON_ARRAY('BARBELL'),          'ACTIVE'),
('BARBELL_DEADLIFT',          '杠铃硬拉',     'STRENGTH', JSON_ARRAY('腘绳肌','臀大肌','竖脊肌'),        JSON_ARRAY('BARBELL'),          'ACTIVE'),
('BARBELL_BENCH_PRESS',       '杠铃卧推',     'STRENGTH', JSON_ARRAY('胸大肌','三角肌前束','肱三头肌'),  JSON_ARRAY('BARBELL','BENCH'),  'ACTIVE'),
('BARBELL_OVERHEAD_PRESS',    '杠铃站姿肩上推','STRENGTH', JSON_ARRAY('三角肌','肱三头肌','斜方肌'),      JSON_ARRAY('BARBELL'),          'ACTIVE'),
('BARBELL_ROW',               '杠铃划船',     'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('BARBELL'),          'ACTIVE'),
('DUMBBELL_BENCH_PRESS',      '哑铃卧推',     'STRENGTH', JSON_ARRAY('胸大肌','三角肌前束','肱三头肌'),  JSON_ARRAY('DUMBBELL','BENCH'), 'ACTIVE'),
('DUMBBELL_ROW',              '哑铃划船',     'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('DUMBBELL','BENCH'), 'ACTIVE'),
('DUMBBELL_REVERSE_LUNGE',    '哑铃后撤步弓步','STRENGTH', JSON_ARRAY('股四头肌','臀大肌','腘绳肌'),      JSON_ARRAY('DUMBBELL'),         'ACTIVE'),
('LAT_PULLDOWN',              '高位下拉',     'STRENGTH', JSON_ARRAY('背阔肌','肱二头肌'),                JSON_ARRAY('CABLE_MACHINE'),    'ACTIVE'),
('SEATED_CABLE_ROW',          '坐姿绳索划船', 'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('CABLE_MACHINE'),    'ACTIVE'),
('LEG_PRESS',                 '腿举',         'STRENGTH', JSON_ARRAY('股四头肌','臀大肌'),                JSON_ARRAY('MACHINE'),          'ACTIVE'),
('PUSH_UP',                   '俯卧撑',       'STRENGTH', JSON_ARRAY('胸大肌','三角肌前束','肱三头肌'),  JSON_ARRAY('BODYWEIGHT'),       'ACTIVE'),
('INVERTED_ROW',              '反向划船',     'STRENGTH', JSON_ARRAY('背阔肌','斜方肌','肱二头肌'),      JSON_ARRAY('BODYWEIGHT'),       'ACTIVE'),
-- 心肺 7 条
('RUNNING',                   '跑步',         'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('OUTDOOR','TREADMILL'), 'ACTIVE'),
('TREADMILL_WALK_INCLINE',    '坡度快走',     'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('TREADMILL'),        'ACTIVE'),
('CYCLING',                   '骑行',         'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('BIKE'),             'ACTIVE'),
('ROWING',                    '划船机',       'CARDIO',   JSON_ARRAY('下肢肌群','背阔肌','心肺'),          JSON_ARRAY('ROWER'),            'ACTIVE'),
('ELLIPTICAL',                '椭圆机',       'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('ELLIPTICAL'),       'ACTIVE'),
('STAIR_CLIMBER',             '楼梯机',       'CARDIO',   JSON_ARRAY('下肢肌群','心肺'),                  JSON_ARRAY('STAIR_CLIMBER'),    'ACTIVE'),
('JUMP_ROPE',                 '跳绳',         'CARDIO',   JSON_ARRAY('小腿','肩部','心肺'),                JSON_ARRAY('JUMP_ROPE'),        'ACTIVE'),
-- 柔韧与活动度 5 条
('CAT_COW',                   '猫牛式',       'MOBILITY', JSON_ARRAY('脊柱','背部'),                      JSON_ARRAY('MAT'),              'ACTIVE'),
('HIP_FLEXOR_STRETCH',        '髋屈肌拉伸',   'MOBILITY', JSON_ARRAY('髂腰肌','股直肌'),                  JSON_ARRAY('MAT'),              'ACTIVE'),
('HAMSTRING_STRETCH',         '腘绳肌拉伸',   'MOBILITY', JSON_ARRAY('腘绳肌'),                            JSON_ARRAY('MAT'),              'ACTIVE'),
('SHOULDER_CROSS_STRETCH',    '肩部横位拉伸', 'MOBILITY', JSON_ARRAY('三角肌','上背'),                    JSON_ARRAY('MAT'),              'ACTIVE'),
('THORACIC_ROTATION',         '胸椎旋转',     'MOBILITY', JSON_ARRAY('胸椎','核心'),                      JSON_ARRAY('MAT'),              'ACTIVE');
