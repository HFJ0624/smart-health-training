-- =============================================================================
-- FitAgent 全量建表脚本（MySQL 8.4，21 张表，V1..V22 顺序）
--
-- 本文件 = docs/sql/p1-init-mysql-8.4.sql（V1..V9，8 张表 + 动作字典种子）
--        + docs/sql/agent-v1-coach-and-foundation-mysql-8.4.sql（V10..V22，13 张表）
-- 内容与 backend/app/src/main/resources/db/migration 下的迁移脚本逐字一致。
--
-- ⚠ 两条路线只选一条：
--   A（推荐）：什么都不执行，让应用启动时 Flyway 自动迁移（fitagent 账号需
--             CREATE/ALTER/INDEX/REFERENCES 等 DDL 权限，FITAGENT_FLYWAY_ENABLED=true）。
--   B：你手工执行本脚本，然后把 FITAGENT_FLYWAY_ENABLED=false。
--     注意 B 的后果：手工建表不会写 flyway_schema_history，之后**不能**再把 Flyway
--     打开（会因对象已存在而失败），除非你愿意补 baseline。所以要么一直 B，要么走 A。
--
-- ⚠ 硬性前提：MySQL 8.4（至少 8.0.16）。
--   utf8mb4_0900_ai_ci 在 5.7 上不存在、CHECK 约束在 8.0.16 之前不生效、
--   active_user_id / active_conversation_id 依赖 STORED 生成列 + 函数唯一索引。
--   本机在用的 5.7 第一步就会报错，不要用。
--
-- 21 张表分三批：
--   基础功能 8：user_account, user_profile, user_goal, body_measurement,
--               exercise_reference(+25 条种子), training_plan, training_plan_day, training_plan_item
--   模型接入与费用 8：model_provider, model_catalog, model_price, model_credential,
--               provider_balance_snapshot, budget_policy, budget_daily_usage, budget_reservation
--   AI 教练 5：chat_conversation, agent_run, model_call, agent_step, chat_message
--
-- ⚠ 本脚本**不含模型与报价的种子数据**（迁移里的 V23 故意没出）：
--   deepseek-v4-pro 由哪个模型服务、按哪档计费，官方两条公告互相矛盾（见
--   docs/agent-v1-protocol.md §1），需要你核对当期计费后再单独插入。
--   在 model_provider / model_catalog / model_price 有生效行之前，
--   后端会以 MODEL_NOT_CONFIGURED 拒绝发起任何模型调用——这是设计，不是故障。
--   每个用户还需要一行 budget_policy（在小程序「我的 → 模型设置」里保存预算即可），
--   否则 BudgetGate 会因"没有生效策略"拒绝调用。
--
-- 全文不含 DROP / DELETE / TRUNCATE。
--
-- 目标库名：smart_health_training（脚本里的 CREATE DATABASE 带 IF NOT EXISTS，
-- 库已存在时这一句是空操作，随后 USE 选中它）。
--
-- ⚠ 本脚本**不是可重复执行的**：所有 CREATE TABLE 都没有 IF NOT EXISTS（与 Flyway
--   迁移保持一致，迁移历史本来就是"每个版本只跑一次"）。如果之前有一次执行到一半失败，
--   再跑一次会在已存在的表上报 1050 Table already exists。先跑下面这句看已经建了哪些：
--
--   SELECT TABLE_NAME FROM information_schema.TABLES
--    WHERE TABLE_SCHEMA='smart_health_training'
--      AND TABLE_NAME IN ('user_account','user_profile','user_goal','body_measurement',
--        'exercise_reference','training_plan','training_plan_day','training_plan_item',
--        'model_provider','model_catalog','model_price','model_credential',
--        'provider_balance_snapshot','budget_policy','budget_daily_usage','budget_reservation',
--        'chat_conversation','agent_run','model_call','agent_step','chat_message');
--
--   缺哪几张就从对应的 V<N> 段单独执行（或把这一段复制出来跑），不要整份重跑，
--   也不要 DROP 已建好的表——里面可能已经有你插入的数据。
--
-- ⚠ exercise_reference 的种子 INSERT 是 25 行固定内容；如果之前已经导过一次，
--   重跑会撞 uq_exercise_reference 唯一键并报 1062，属于预期保护，不是错误。
-- =============================================================================

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

-- ==========================================================================
-- ↓ 以下来自 V10..V22：模型接入、费用地基与 AI 教练（13 张表）
-- ==========================================================================

-- =============================================================================
-- FitAgent Agent 第一版 · 模型接入、费用地基与会话/run 增量脚本（MySQL 8.4）
--
-- 由 backend/app/src/main/resources/db/migration 下 V10..V22 逐字拼接生成，
-- 内容与 Flyway 迁移一致；两条路线只选一条（手工执行本脚本后设
-- FITAGENT_FLYWAY_ENABLED=false，或让应用启动时自动迁移）。
--
-- V10..V17 是模型接入与费用地基 8 张表：
--   model_provider / model_catalog / model_price / model_credential
--   provider_balance_snapshot / budget_policy / budget_daily_usage / budget_reservation
-- V18..V22 是会话与 run 5 张表：
--   chat_conversation / agent_run / model_call / agent_step / chat_message
--
-- 派生列提醒：agent_run.active_conversation_id 是 STORED 生成列（会话内活动 run 的唯一闸门），
-- 任何 INSERT/UPDATE 都不得带它；chat_conversation / chat_message 等表同样依赖
-- UNIQUE(id, user_id) 做跨模块归属外键。
--
-- 模型 ID、档位与报价的**种子数据不在这里**：以 docs/agent-v1-protocol.md 的核查结果为准，
-- 且 deepseek-v4-pro 的价格行默认 enabled=false（官方两处公告互相矛盾，未经核对不得启用）。
-- 不含 DROP / DELETE / TRUNCATE。
-- =============================================================================

USE `smart_health_training`;
SET NAMES utf8mb4;
SET time_zone = '+00:00';


-- -----------------------------------------------------------------------------
-- >>> V10__create_model_provider.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V10：model_provider（模型供应商白名单）
-- 依赖：V1（user_account）。本表不引用任何用户表——它是后端侧的系统配置，运维写入，用户只读。
--
-- 为什么 base_url 必须在表里而不是由请求决定（docs/agent-v1-design.md §7 V10）：
--   余额查询与补全外呼的地址只能来自这张表，与微信 code2Session 同一手法——
--   "由输入决定外呼地址"会把服务端变成 SSRF 跳板，因此本列没有任何接口写入路径。
--
-- balance_supported 为 FALSE 时网关不发起余额外呼：把一个不支持该接口的地址标成"可用能力"
-- 会产出一张永远失败的按钮，比不提供更糟。
--
-- 本轮没有 provider 种子数据：取值必须来自 docs/agent-v1-protocol.md 的官方核查
-- （docs/agent-v1-design.md §7 V23），核查文档尚未产出，因此该表当前为空，
-- 任何依赖它的接口如实报 MODEL_NOT_CONFIGURED，不做任何默认猜测。
-- =============================================================================

CREATE TABLE `model_provider` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `provider_code`     VARCHAR(40)  NOT NULL COMMENT '供应商标识码，约定大写（如 DEEPSEEK）；列保持表默认 CI 排序规则，服务端先规范化大写再匹配（docs/p1-database.md §5）',
  `display_name`      VARCHAR(80)  NOT NULL COMMENT '界面显示名；纯展示，不参与任何匹配',
  `base_url`          VARCHAR(500) NOT NULL COMMENT '外呼基地址；只由运维写入，任何接口都不接受客户端改写',
  `balance_supported` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否提供余额查询接口；FALSE 时网关不外呼，直接报 BALANCE_UNAVAILABLE',
  `adapter_code`      VARCHAR(40)  NOT NULL COMMENT '适配器代码（如 DEEPSEEK）；决定用哪个协议实现，不由用户选择',
  `status`            ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '供应商状态；DISABLED 时不可选、不可外呼',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_provider_1` (`provider_code`),
  -- 白名单地址必须是 https：凭据以 Bearer 头外发，明文 http 等于把 API Key 发给链路任何人
  CONSTRAINT `ck_model_provider_0` CHECK (`base_url` LIKE 'https://%'),
  -- 基地址只到主机与路径前缀，不允许携带查询串（否则凭据会被拼进可被改写的外部地址）
  CONSTRAINT `ck_model_provider_1` CHECK (`base_url` NOT LIKE '%?%'),
  CONSTRAINT `ck_model_provider_2` CHECK (`adapter_code` <> '' AND `provider_code` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型供应商白名单';

-- -----------------------------------------------------------------------------
-- >>> V11__create_model_catalog.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V11：model_catalog（模型能力目录）
-- 依赖 V10（model_provider）。
--
-- 取值依据：docs/agent-v1-protocol.md §1 —— 当前只有 deepseek-flash 与 deepseek-v4-pro
-- （deepseek-chat / deepseek-reasoner 已于 2026-07-24 停用）。
-- 本表是这些事实的唯一存放处：代码里不写死模型名、上下文与输出上限
-- （docs/agent-v1-design.md §5 第 8 条）。因此本轮没有目录种子数据也无妨——
-- 读不到 ACTIVE 模型就报 MODEL_NOT_CONFIGURED，绝不回退到内置默认模型。
--
-- 三个 supports_* 是布尔开关而不是提示文字：
--   supports_tools 决定工具调用能否开启；supports_json 决定结构化输出；
--   supports_vision 本轮没有任何图片入口（设计§1"不做图片解析"），列存在只是因为
--   协议核查已给出真实取值，页面据此显示"本项目不使用"。
--
-- capability_snapshot 存核查到的原始字段快照（含来源与核查上下文），
-- 与三个派生布尔列并存的理由：派生列可被 SQL 谓词使用，快照保留"我们据以得出该结论的原文"，
-- 事后发现派生口径错了可以回溯。形状由 docs/agent-v1-protocol.md 约束，不在 DDL 里用函数 CHECK 强检
-- （本机无法连到 8.4 验证函数式 CHECK，宁可不写也不写一条可能让迁移失败的约束）。
-- =============================================================================

CREATE TABLE `model_catalog` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `provider_id`        BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `model_code`         VARCHAR(80)  NOT NULL COMMENT '请求体 model 字段的取值（如 deepseek-flash）；列保持表默认 CI 排序规则，服务端规范化后精确匹配，不做近似匹配',
  `display_name`       VARCHAR(120) NOT NULL COMMENT '界面显示名（可为官方"实际模型版本"，如 DeepSeek-V4.1-Flash）',
  `supports_vision`    BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持图片输入；本轮无图片入口，取值仅作真实登记',
  `supports_tools`     BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持原生 tool calls；不支持的工具调用不能进入执行循环',
  `supports_json`      BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持 JSON Output',
  `max_context_tokens` INT UNSIGNED NOT NULL COMMENT '上下文窗口上限（协议 §1：1048576）；用于历史裁剪，不用于放宽任何业务阈值',
  `max_output_tokens`  INT UNSIGNED NOT NULL COMMENT '单次输出上限（协议 §1：393216）；预算保守预占按 budget_policy.max_output_tokens，不取本列',
  `capability_snapshot` JSON NOT NULL COMMENT '核查原文快照；来源与字段口径见 docs/agent-v1-protocol.md §1，NOT NULL 强制每次登记都带出处',
  `source_checked_on`  DATE NOT NULL COMMENT '能力核查日期（不是创建时间）；过期后由运维重新核对',
  `status`             ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '模型状态；DISABLED 时不可选',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_catalog_1` (`provider_id`, `model_code`),
  -- 目录读取的固定形态：给定供应商取 ACTIVE 模型，按核查日期与 id 确定性择一
  KEY `idx_model_catalog_2` (`provider_id`, `status`, `source_checked_on`),
  CONSTRAINT `ck_model_catalog_0` CHECK (`max_context_tokens` > 0),
  CONSTRAINT `ck_model_catalog_1` CHECK (`max_output_tokens` > 0),
  CONSTRAINT `ck_model_catalog_2` CHECK (`max_output_tokens` <= `max_context_tokens`),
  CONSTRAINT `fk_model_catalog_0` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型能力目录';

-- -----------------------------------------------------------------------------
-- >>> V12__create_model_price.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V12：model_price（版本化报价 + 峰谷档位）
-- 依赖 V11（model_catalog）。
--
-- 档位为什么在表里：docs/agent-v1-protocol.md §7 核实"峰谷分时计价是官方真实机制"
-- （峰=北京时间周一至周五 09:00–12:00、14:00–18:00，不含法定节假日；谷=峰的 50%），
-- 这更正了设计文档 §6.2 原先"不含 rate_tier"的判断。
-- 同时 §7 明言：中国法定节假日日历没有核实到的机器可读来源，因此不入库、不猜测，
-- 所以这里没有 schedule_policy / holiday_calendar 之类的列，也没有档位判定表。
-- 后果（必须知情）：预占一律按 PEAK 档（见 modules/billing 的保守估算），
-- 结算时档位可确定才用实际档，不确定就标"保守核算"，不冒充供应商账单。
--
-- enabled 默认 FALSE：报价必须"核查 + 运维显式启用"两个动作都完成才生效。
-- 协议 §1 记录 deepseek-v4-pro 的当期适用性存在官方自相矛盾（news260910 vs 2026-09-10 changelog），
-- 其种子行必须保持 enabled=false 由用户核对后启用；没有任何 enabled 报价时接口报
-- MODEL_NOT_CONFIGURED，绝不使用代码内的默认价。
--
-- 三个单价都用 DECIMAL(20,10)：单位是"每 100 万 token 的金额"，
-- 实际费用 = token 数 × 单价 / 1,000,000，结果仍是 10 位小数可精确表示的量级
-- （协议 §7 最小档 0.02 元/1M → 单 token 2e-8，10 位小数够；再小就是"低于 0.0001 元"的显示问题，
--  由前端按 docs/agent-v1-design.md §12 处理，不在这里 round 成 0）。
-- =============================================================================

CREATE TABLE `model_price` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `model_id`              BIGINT UNSIGNED NOT NULL COMMENT '所属模型；FK → model_catalog.id',
  `price_version`         INT UNSIGNED NOT NULL COMMENT '同一模型内的报价版本；官方调价时新增版本而不是改写历史行',
  `currency`              CHAR(3) NOT NULL COMMENT 'ISO-4217 币种（协议 §8：CNY 或 USD）；服务端规范化大写。不同币种永不相减、不猜汇率',
  `rate_tier`             ENUM('PEAK','OFF_PEAK') NOT NULL COMMENT '计价档位；预占恒按 PEAK 保守档（节假日日历未核实，不能断定某时刻在谷段）',
  `input_hit_per_million` DECIMAL(20,10) NOT NULL COMMENT '输入(缓存命中)单价，元/1M tokens；对应 usage.prompt_cache_hit_tokens',
  `input_miss_per_million` DECIMAL(20,10) NOT NULL COMMENT '输入(未命中)单价，元/1M tokens；对应 usage.prompt_cache_miss_tokens',
  `output_per_million`    DECIMAL(20,10) NOT NULL COMMENT '输出单价，元/1M tokens；对应 usage.completion_tokens。reasoning_tokens 是其细分项，不另计价（协议 §6）',
  `source_url`            VARCHAR(1000) NOT NULL COMMENT '报价来源页 URL；每个数都要能追溯到官方出处',
  `source_checked_on`     DATE NOT NULL COMMENT '人工核查日期（官方页未标注自身日期，故记核查当日）',
  `enabled`               BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否生效；默认 false，必须显式启用，避免未核查的报价进入计费',
  `created_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 一个模型一个版本一个币种一个档位只有一条报价；档位必须进唯一键，否则 PEAK/OFF_PEAK 两行无法共存
  UNIQUE KEY `uq_model_price_1` (`model_id`, `price_version`, `currency`, `rate_tier`),
  -- "取该模型当前生效报价"的固定查询形态
  KEY `idx_model_price_2` (`model_id`, `enabled`, `price_version`),
  CONSTRAINT `ck_model_price_0` CHECK (`price_version` > 0),
  CONSTRAINT `ck_model_price_1` CHECK (`input_hit_per_million` >= 0),
  CONSTRAINT `ck_model_price_2` CHECK (`input_miss_per_million` >= 0),
  CONSTRAINT `ck_model_price_3` CHECK (`output_per_million` >= 0),
  CONSTRAINT `ck_model_price_4` CHECK (`source_url` LIKE 'https://%'),
  CONSTRAINT `fk_model_price_0` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本化模型报价（含峰谷档位）';

-- -----------------------------------------------------------------------------
-- >>> V13__create_model_credential.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V13：model_credential（用户自带的模型 API Key，BYOK）
-- 依赖 V1（user_account）、V10（model_provider）。
--
-- 这里存的是"别人家的钱包钥匙"，因此列集合就是安全边界的表达（docs/agent-v1-design.md §6.1）：
--   ciphertext / nonce  —— AES-256-GCM 密文与每次加密新取的 12 字节随机 nonce；
--   key_fingerprint     —— HMAC-SHA256（独立密钥）的 32 字节原始摘要，只用于判断"是不是同一把 Key"，
--                          不可反推明文，且任何读取接口都不返回它；
--   key_suffix          —— 最多 8 字符的尾部，仅用于让用户认出"我存的是哪把"；
--   encryption_key_version —— 主密钥轮换后据此知道旧行是用哪一代密钥封的（本轮不实现轮换，只留位）；
--   credential_version  —— 同一行的替换次数：run 创建时绑定该版本，执行中不悄悄换 Key，
--                          且 GCM 的 AAD 绑定了它，跨版本搬运密文会当场解密失败。
-- 表中没有任何明文列，也没有任何接口能写 base_url 之类的东西。
--
-- 撤销（revoked）会把 ciphertext 与 nonce 置 NULL：密文都不在了，密钥就真的不可用，
-- 而不是"应用记得不用它"。这也是 ck_model_credential_0 的用途——
-- ACTIVE 行必须同时有密文与 nonce，防止出现"状态可用但没东西可解密"的自欺状态。
--
-- 微信身份列的教训不重复：key_suffix 是用户密钥的一部分，大小写有意义，
-- 因此显式 utf8mb4_bin（只做 trim，绝不折叠大小写），与 wechat_open_id 同一口径。
-- =============================================================================

CREATE TABLE `model_credential` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`                BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得，不接受请求参数',
  `provider_id`            BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `label`                  VARCHAR(80) NULL COMMENT '用户自取的备注名；NULL 表示未填写',
  `key_suffix`             VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT 'Key 尾部（最多 8 字符，且刻意短于整把 Key）；大小写逐字符精确',
  `key_fingerprint`        BINARY(32) NOT NULL COMMENT 'HMAC-SHA256 原始 32 字节（独立指纹密钥）；只比较是否同一把，永不返回给客户端',
  `ciphertext`             BLOB NULL COMMENT 'AES-256-GCM 密文（含 16 字节认证标签）；撤销时置 NULL；永不出现在响应与日志',
  `nonce`                  BINARY(12) NULL COMMENT '本次加密使用的随机 nonce；每次替换都重新生成；撤销时置 NULL',
  `encryption_key_version` VARCHAR(40) NOT NULL COMMENT '主密钥版本（fitagent.modelaccess.key-version）；为将来的密钥轮换留位',
  `credential_version`     INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '凭据版本号：首次为 1，每次替换 +1；run 绑定该值，解密时校验，防止中途换 Key',
  `status`                 ENUM('ACTIVE','INVALID','REVOKED') NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 可用；INVALID 被供应商拒绝（401，需用户重新配置）；REVOKED 用户已撤销',
  `validated_at`           DATETIME(3) NULL COMMENT '服务端最后一次写入/替换该凭据的 UTC 时间；本轮不做在线校验，因此它表示落库时间而非供应商确认时间',
  `revoked_at`             DATETIME(3) NULL COMMENT '撤销时间；非 REVOKED 时恒为 NULL',
  `lock_version`           INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 一个用户在一个供应商只有一把在用 Key：替换发生在同一行并递增 credential_version，
  -- 这样"每用户每供应商一把可用凭据"是数据库事实，不是应用层自觉
  UNIQUE KEY `uq_model_credential_1` (`user_id`, `provider_id`),
  -- 子资源归属复合键（沿用 P1 已验证做法）：(id,user_id) 让未来 model_call 能建复合外键
  UNIQUE KEY `uq_model_credential_2` (`id`, `user_id`),
  KEY `idx_model_credential_3` (`user_id`),
  -- ACTIVE 必须有可解密的材料；REVOKED 行由应用把密文与 nonce 清空（本约束不禁止清空后的状态）
  CONSTRAINT `ck_model_credential_0` CHECK (`status` <> 'ACTIVE' OR (`ciphertext` IS NOT NULL AND `nonce` IS NOT NULL)),
  CONSTRAINT `ck_model_credential_1` CHECK (`credential_version` > 0),
  CONSTRAINT `ck_model_credential_2` CHECK (`status` <> 'REVOKED' OR `revoked_at` IS NOT NULL),
  CONSTRAINT `fk_model_credential_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_model_credential_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户模型 API Key（AES-256-GCM 信封）';

-- -----------------------------------------------------------------------------
-- >>> V14__create_provider_balance_snapshot.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V14：provider_balance_snapshot（供应商余额快照）
-- 依赖 V1、V10。
--
-- 语义先说清楚（docs/agent-v1-protocol.md §8）：
--   1. 这是供应商账户级余额，不是单把 Key 的额度，也可能被本应用之外的调用改变；
--      因此它只是"上一次问供应商时它怎么说的"，绝不参与预算判定（预算看的是本应用账本）。
--   2. 官方字段是 is_available（余额是否足以继续调用），is_expired 不存在于当前任何文档页，
--      旧设计包写错了，本表不予实现。界面上的"陈旧"标记由应用推导
--      （fetched_at 与当前时间之差），不是库存列。
--   3. 三个金额官方返回字符串，落库为 DECIMAL(20,10)，不用 double（协议 §8 明确要求 BigDecimal 绑定）。
--   4. NULL 表示"未提供该分项"，与 0 完全不同：0 是"确实没钱了"，NULL 是"不知道"。
--      查询失败时不落新行，因此"上一次的值 + 陈旧标记"始终可展示，永不被 0 覆盖。
--
-- 一个 balance_infos[] 元素一行（协议 §8 的 currency 可为 CNY 或 USD，可能同时出现两个币种），
-- 因此唯一键必须含 currency：否则同一次抓取的第二种币种会撞键，把一次正常查询变成失败。
-- 跨币种永不相减（docs/agent-v1-design.md §6.3）。
--
-- source_http_status / source_error_code 记录这一行的出处：只有成功的抓取才会落行，
-- 因此它们保存的是"被接受的那次响应"的 HTTP 状态与供应商自报结果码，用于事后追溯，
-- 失败信息不进表（失败不留痕是本表的约定，见上）。
-- =============================================================================

CREATE TABLE `provider_balance_snapshot` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属用户（谁的 Key 问出来的余额）；从登录上下文取得',
  `provider_id`      BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `currency`         CHAR(3) NOT NULL COMMENT '该分项币种（CNY / USD）；服务端规范化大写，与预算币种不同时分开显示',
  `total_balance`    DECIMAL(20,10) NULL COMMENT '总余额；NULL 表示供应商未给出该分项，不当 0',
  `granted_balance`  DECIMAL(20,10) NULL COMMENT '赠送余额；官方扣费顺序是先赠送后充值（协议 §7）',
  `topped_up_balance` DECIMAL(20,10) NULL COMMENT '充值余额',
  `is_available`     BOOLEAN NULL COMMENT '供应商报告的"余额是否足以继续调用"；NULL 表示响应里没有该字段',
  `fetched_at`       DATETIME(3) NOT NULL COMMENT 'UTC 抓取时刻；同时是"最新快照"的排序依据',
  `source_http_status` SMALLINT UNSIGNED NULL COMMENT '被接受的响应 HTTP 状态（成功路径恒 200）',
  `source_error_code`  VARCHAR(64) NULL COMMENT '供应商在 200 响应内自报的结果码原文；没有则为 NULL，不猜测',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 同一次抓取（同一 fetched_at）内一个币种只有一行；该索引同时服务
  -- "WHERE user_id=? AND provider_id=? AND currency=? ORDER BY fetched_at DESC LIMIT 1"（8.4 可反向扫描）
  UNIQUE KEY `uq_provider_balance_snapshot_1` (`user_id`, `provider_id`, `currency`, `fetched_at`),
  UNIQUE KEY `uq_provider_balance_snapshot_2` (`id`, `user_id`),
  KEY `idx_provider_balance_snapshot_3` (`user_id`),
  CONSTRAINT `ck_provider_balance_snapshot_0` CHECK (`total_balance` IS NULL OR `total_balance` >= 0),
  CONSTRAINT `ck_provider_balance_snapshot_1` CHECK (`granted_balance` IS NULL OR `granted_balance` >= 0),
  CONSTRAINT `ck_provider_balance_snapshot_2` CHECK (`topped_up_balance` IS NULL OR `topped_up_balance` >= 0),
  CONSTRAINT `fk_provider_balance_snapshot_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_provider_balance_snapshot_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商账户余额快照（失败不清零）';

-- -----------------------------------------------------------------------------
-- >>> V15__create_budget_policy.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V15：budget_policy（用户预算策略）
-- 依赖 V1、V10。
--
-- 这是 BYOK 下唯一能挡住"跑飞"的闸门（docs/agent-v1-design.md §6.3：BudgetGate 不是默认放行）。
-- 语义必须钉死，否则会变成假保护：
--   max_task_cost / max_daily_cost 为 NULL 表示"该维度不设上限"，不是 0、不是"不限=随便花"里的 0；
--   两者同时为 NULL 时 BudgetGate 仍然拒绝发起调用——一行没有任何上限的策略等价于"默认放行"，
--   而设计明确禁止默认放行，因此策略必须至少给出一个上限才可用（代码侧校验，见 BudgetGate）。
--   enabled=false 同样拒绝发起：关掉预算控制不等于放行控制，用户想花就得显式开着。
--
-- currency 进唯一键：不同币种是彼此独立的账户，永不相减、不猜汇率（设计 §6.3）。
-- budget_timezone 固定 Asia/Shanghai（设计 §7 V15），因此本列没有任何写入路径，
-- 日账本归属完全由服务端按该时区从"调用开始时刻"推导，客户端不传日期。
-- max_model_calls / max_tool_calls / max_output_tokens 是工程闸门（与 RunLimits 同源），
-- 不是健康阈值，也不是供应商侧的硬额度。
-- =============================================================================

CREATE TABLE `budget_policy` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id`       BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `currency`          CHAR(3) NOT NULL COMMENT '限额币种（服务端规范化大写）；必须与 model_price.currency 一致才可比',
  `max_task_cost`     DECIMAL(20,10) NULL COMMENT '单次任务（一个 run 的全部模型调用之和）上限；NULL 表示该维度不设限',
  `max_daily_cost`    DECIMAL(20,10) NULL COMMENT '当日上限（按 budget_timezone 的业务日）；NULL 表示该维度不设限',
  `budget_timezone`   VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '业务日推导时区；本轮固定 Asia/Shanghai，无写入路径',
  `max_model_calls`   TINYINT UNSIGNED NOT NULL DEFAULT 4 COMMENT '单个 run 允许的模型调用次数上限；工程闸门',
  `max_tool_calls`    TINYINT UNSIGNED NOT NULL DEFAULT 8 COMMENT '单个 run 允许的工具执行次数上限；工程闸门',
  `max_output_tokens` INT UNSIGNED NOT NULL DEFAULT 1024 COMMENT '保守预占使用的输出上限（不取 model_catalog.max_output_tokens，那是供应商硬上限）',
  `enabled`           BOOLEAN NOT NULL DEFAULT TRUE COMMENT '策略是否生效；false 时 BudgetGate 拒绝发起调用，而不是无视预算',
  `lock_version`      INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 一个用户在一个供应商的一个币种上只有一份在用策略
  UNIQUE KEY `uq_budget_policy_1` (`user_id`, `provider_id`, `currency`),
  UNIQUE KEY `uq_budget_policy_2` (`id`, `user_id`),
  KEY `idx_budget_policy_3` (`user_id`),
  CONSTRAINT `ck_budget_policy_0` CHECK (`max_task_cost` IS NULL OR `max_task_cost` > 0),
  CONSTRAINT `ck_budget_policy_1` CHECK (`max_daily_cost` IS NULL OR `max_daily_cost` > 0),
  CONSTRAINT `ck_budget_policy_2` CHECK (`max_model_calls` > 0),
  CONSTRAINT `ck_budget_policy_3` CHECK (`max_tool_calls` > 0),
  CONSTRAINT `ck_budget_policy_4` CHECK (`max_output_tokens` > 0),
  CONSTRAINT `fk_budget_policy_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_budget_policy_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户预算策略（不是默认放行）';

-- -----------------------------------------------------------------------------
-- >>> V16__create_budget_daily_usage.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V16：budget_daily_usage（当日预算账户）
-- 依赖 V1、V10、V15（同一组归属键，但本表不引用策略行：策略被删也不该让账本悬空）。
--
-- 三列分账是这套预算能"不重复扣账"的关键（docs/agent-v1-design.md §6.3）：
--   settled_cost            —— 已按实际 usage 核算落定的钱（真金白银）
--   reserved_cost           —— 已预占、请求在途或结果还好的钱（保守估算额）
--   uncertain_reserved_cost —— 已发出但 usage 不明的钱：保留估算额，不重发、也不归零
-- 可用额度 = 限额 − (settled + reserved + uncertain_reserved)。三者缺一都会变成"默认放行"，
-- 尤其是 uncertain：把它当 0 就等于允许用户为一次可能已扣费的调用再花一次预算。
--
-- 本行是并发预占的串行点：BudgetGate 在同一短事务里 INSERT … ON DUPLICATE KEY 分配该行
-- 再 SELECT … FOR UPDATE 锁住它，因此同一天账户上的两次并发预占不会互相看不见（不会双花）。
-- 三列都 >= 0 是硬约束：转移公式（reserved→settled 等）一旦写错，数据库会当场拒绝而不是
-- 悄悄给出一个负数额度。
--
-- budget_date 由服务端按 budget_policy.budget_timezone（本轮 Asia/Shanghai）从"调用开始时刻"推导，
-- 客户端不传；跨午夜的长任务里每次调用各归自己的开始日，单次任务上限仍覆盖全部调用（设计 §6.3）。
-- =============================================================================

CREATE TABLE `budget_daily_usage` (
  `id`                    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`               BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `currency`              CHAR(3) NOT NULL COMMENT '账户币种；不同币种是不同账户行，永不相减、不猜汇率',
  `budget_date`           DATE NOT NULL COMMENT '业务预算日；服务端按用户预算时区从调用开始时刻推导',
  `settled_cost`          DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '已核算落定费用',
  `reserved_cost`         DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '在途预占（保守估算额）',
  `uncertain_reserved_cost` DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '用量不明的预占：仍占用额度，不重发',
  `lock_version`          INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；并发预占的串行点，更新时递增',
  `created_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`            DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 一个用户/供应商/币种/业务日只有一个账户行；该索引同时覆盖"取当日账户"的 FOR UPDATE 查询
  UNIQUE KEY `uq_budget_daily_usage_1` (`user_id`, `provider_id`, `currency`, `budget_date`),
  UNIQUE KEY `uq_budget_daily_usage_2` (`id`, `user_id`),
  -- 日账本页面的查询形态：某人某供应商按日期倒序
  KEY `idx_budget_daily_usage_3` (`user_id`, `provider_id`, `budget_date`),
  CONSTRAINT `ck_budget_daily_usage_0` CHECK (`settled_cost` >= 0),
  CONSTRAINT `ck_budget_daily_usage_1` CHECK (`reserved_cost` >= 0),
  CONSTRAINT `ck_budget_daily_usage_2` CHECK (`uncertain_reserved_cost` >= 0),
  CONSTRAINT `fk_budget_daily_usage_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_budget_daily_usage_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当日预算账户（三列分账）';

-- -----------------------------------------------------------------------------
-- >>> V17__create_budget_reservation.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V17：budget_reservation（逐次调用的预占台账）
-- 依赖 V1、V10、V16。
--
-- run_id 是普通 BIGINT UNSIGNED 列，没有外键：agent_run 本轮还不存在（设计 §7 把它排在 V18）。
-- 因此本表只保证"同一个 (run_id, attempt_no) 只有一条预占"，跨表归属由应用侧
-- WHERE run_id = ? AND user_id = ? 的双条件限定；agent_run 落地后应补一条复合外键（届时另出迁移，
-- 不回改本文件——迁移一律向前）。
--
-- 结算幂等的数据库防线在这里，不在 model_call（该表本轮不建）：
--   status 是唯一状态机（RESERVED → SETTLED / UNCERTAIN / RELEASED），
--   任何转移都用 "WHERE id=? AND user_id=? AND status=<期望值>" 的比较并交换完成，
--   影响 0 行就说明这次回调重复或顺序错了，账本不会动第二次。
--   ck_2 把 SETTLED 与"实际金额 + 落定时刻"绑死：没有这两个值就不允许自称已结算。
--
-- estimate_quality 的取值口径（协议 §7：节假日日历未核实 → 预占恒按 PEAK 档）：
--   CONSERVATIVE 预占时的保守估算（输入全按未命中价、输出按 max_output_tokens 全价、档位取 PEAK）
--   ACTUAL       已按本次真实 usage 核算（仍不称为"供应商账单"，供应商结算记录才是最终依据）
--   UNKNOWN      usage 不明：保留估算额，既不释放也不当已花
-- reserved_amount 落库后不再改写——它是"当初占了多少"的凭证，改了日账户的分账就复现不出来。
--
-- 版本号说明：设计 §7 的排期把 budget_reservation 记作 V19、chat_conversation 记作 V17。
-- 本表与 modelaccess/billing 的其余表必须连续（V10–V17），故占用 V17；
-- chat_conversation / agent_run / model_call / agent_step / chat_message 从 V18 起顺延，
-- 不要再新建第二个 V17（Flyway 同版本号会直接启动失败）。
-- =============================================================================

CREATE TABLE `budget_reservation` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得，所有查询都以它为条件',
  `run_id`           BIGINT UNSIGNED NOT NULL COMMENT '所属 run；agent_run 本轮不存在，故不建外键，仅做逻辑归属',
  `attempt_no`       INT UNSIGNED NOT NULL COMMENT '该 run 内的第几次模型调用（从 1 起）；受 budget_policy.max_model_calls 约束',
  `provider_id`      BIGINT UNSIGNED NOT NULL COMMENT '所属供应商；FK → model_provider.id',
  `daily_usage_id`   BIGINT UNSIGNED NOT NULL COMMENT '所归属的当日账户行；FK → budget_daily_usage.id，预占/结算都锁这一行',
  `currency`         CHAR(3) NOT NULL COMMENT '币种；必须与当日账户行一致，跨币种永不相减',
  `budget_date`      DATE NOT NULL COMMENT '业务预算日（调用开始时刻按预算时区推导），冗余自账户行便于对账',
  `reserved_amount`  DECIMAL(20,10) NOT NULL COMMENT '本次预占额（保守估算）；写入后不改写',
  `settled_amount`   DECIMAL(20,10) NULL COMMENT '本次实际核算额；NULL 表示尚未落定（不是 0）',
  `estimate_quality` ENUM('CONSERVATIVE','ACTUAL','UNKNOWN') NOT NULL COMMENT '金额质量：保守估算 / 已按实际 usage 核算 / 用量不明',
  `status`           ENUM('RESERVED','SETTLED','UNCERTAIN','RELEASED') NOT NULL DEFAULT 'RESERVED' COMMENT '预占状态机；转移用比较并交换完成',
  `settled_at`       DATETIME(3) NULL COMMENT 'UTC 落定时刻；仅 SETTLED 非空',
  `lock_version`     INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 同一次调用的重复预占请求不会产生两条账（重试走幂等，不靠应用记得查）
  UNIQUE KEY `uq_budget_reservation_1` (`run_id`, `attempt_no`),
  UNIQUE KEY `uq_budget_reservation_2` (`id`, `user_id`),
  -- 单次任务费用的求和形态：一个 run 的全部预占
  KEY `idx_budget_reservation_3` (`user_id`, `run_id`),
  -- 当日账户的台账（排查双花与对账用）
  KEY `idx_budget_reservation_4` (`daily_usage_id`),
  CONSTRAINT `ck_budget_reservation_0` CHECK (`attempt_no` > 0),
  CONSTRAINT `ck_budget_reservation_1` CHECK (`reserved_amount` >= 0),
  CONSTRAINT `ck_budget_reservation_2` CHECK (`settled_amount` IS NULL OR (`settled_amount` >= 0 AND `settled_at` IS NOT NULL)),
  CONSTRAINT `ck_budget_reservation_3` CHECK (`status` <> 'SETTLED' OR `estimate_quality` = 'ACTUAL'),
  CONSTRAINT `ck_budget_reservation_4` CHECK (`status` <> 'RELEASED' OR `settled_amount` IS NULL),
  CONSTRAINT `fk_budget_reservation_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_budget_reservation_1` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`),
  CONSTRAINT `fk_budget_reservation_2` FOREIGN KEY (`daily_usage_id`, `user_id`) REFERENCES `budget_daily_usage` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐次调用的预算预占台账';

-- -----------------------------------------------------------------------------
-- >>> V18__create_chat_conversation.sql
-- -----------------------------------------------------------------------------

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

-- -----------------------------------------------------------------------------
-- >>> V19__create_agent_run.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V19：agent_run（一次提问的后台执行任务）
-- 依赖 V1、V11、V13、V18。对应设计 §7、§10。
--
-- 三条数据库级不变式（不是应用层的"记得检查"）：
--
-- 1. 单会话只有一个活动 run —— `active_conversation_id` 是 STORED 生成列，
--    与 user_goal.active_user_id 完全同一手法（AGENTS.md：任何 INSERT/UPDATE 都不能带它）：
--      status ∈ (QUEUED,RUNNING) → 取 conversation_id，否则 NULL。
--    UNIQUE 里 NULL 不参与冲突，所以终态 run 再多也不挡；同一个会话的第二个 QUEUED/RUNNING 直接撞索引。
--    应用层据此"捕获重复键 → RUN_ALREADY_ACTIVE"，而不是先查再插（先查再插在并发下必然两个都通过）。
--
-- 2. 幂等 —— UNIQUE(user_id, idempotency_key)。同键同 request_hash 返回同一 run；
--    同键不同 hash 由应用判定为 IDEMPOTENCY_KEY_CONFLICT，绝不返回另一个问题的结果。
--    request_hash = SHA-256(conversationId | 规范化问题文本 | 语言/日期参数) 的 64 位十六进制，
--    **不含任何凭据材料**（设计 §6.1：Key 不进库、不进哈希输入、不进日志）。
--
-- 3. 归属 —— (id, user_id) 复合唯一 + 子表复合外键；run_uuid 是对外标识（BIGINT 主键不外泄自增顺序）。
--
-- 费用三列的口径（设计 §6.2、§11.1 规范化决定第 1 条）：
--   estimated_cost  保守预占口径的预估（发送前显示；NULL = 当时无法核算，不是 0）
--   settled_cost    已按真实 usage 结算之和
--   uncertain_cost  已发出但用量不明的估算之和（不归零、不重发）
-- 三列都只在 run 行上，AgentAnswer 里没有费用字段——两处都能放就一定漂移。
-- `currency` 独立成列：响应必须带币种，而跨币种永不相减（设计 §6.3），不能靠解析 JSON 猜。
--
-- result_payload 存**完整** AgentAnswer（事实卡片、来源、缺失项、data_revision、stop_reason），
-- 页面重进读同一份持久化结果，不依赖内存、也不拿 agent_step.result_summary 的截断文本冒充。
-- 事实快照一旦写入不再被静默重写，费用状态仍可更新（设计 §7）。
--
-- worker_id / lease_expires_at 是恢复判定的依据（设计 §10）：领取用条件更新（0 行即放弃），
-- 心跳只续租 lease_expires_at；失去 Worker 的 RUNNING 行由启动扫描器按 model_call 状态裁决。
-- =============================================================================

CREATE TABLE `agent_run` (
  `id`                     BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；对外用 run_uuid，接口响应里的 runId 是它的字符串形式',
  `run_uuid`               CHAR(36) NOT NULL COMMENT '对外的任务标识（服务端生成的小写 UUID）',
  `user_id`                BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `conversation_id`        BIGINT UNSIGNED NOT NULL COMMENT '所属会话；FK → chat_conversation(id, user_id)',
  `idempotency_key`        CHAR(36) NOT NULL COMMENT '客户端幂等键（小写规范化 UUID）；同键同 hash 返回同一 run',
  `request_hash`           CHAR(64) NOT NULL COMMENT 'SHA-256(会话号|规范化问题文本|语言与日期参数) 十六进制；不含凭据',
  `credential_id`          BIGINT UNSIGNED NULL COMMENT 'run 创建时绑定的凭据行；FK → model_credential(id, user_id)；无可用凭据时为 NULL',
  `credential_version`     INT UNSIGNED NULL COMMENT '绑定的凭据版本；执行中不悄悄换 Key（设计 §6.1）',
  `model_id`               BIGINT UNSIGNED NULL COMMENT '模型目录行；FK → model_catalog.id；能力与输出上限只可能来自这张表',
  `status`                 ENUM('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','BUDGET_STOPPED','LIMIT_STOPPED','DEADLINE_STOPPED','WAITING_CREDENTIAL','COST_UNKNOWN') NOT NULL DEFAULT 'QUEUED' COMMENT 'run 状态机；除 QUEUED/RUNNING 外都是终态',
  `active_conversation_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` IN ('QUEUED','RUNNING') THEN `conversation_id` ELSE NULL END) STORED COMMENT '数据库派生：会话内活动 run 闸门；ORM 不声明、任何写入都不带它',
  `budget_snapshot`        JSON NOT NULL COMMENT 'run 创建时的预算侧快照（策略上限、币种、PEAK 单价、保守估算）；执行时的真实闸门仍逐次生效，本列只用于展示与追溯',
  `currency`               CHAR(3) NULL COMMENT '预算币种（ISO-4217 大写）；与 model_price/budget_policy 同源，跨币种永不相减',
  `context_revision`       BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'run 开始时的 user_account.data_revision；装配 AgentContext 与陈旧判定都以它为准（结束时再读一次比对）',
  `result_payload`         JSON NULL COMMENT '完整 AgentAnswer 快照（终态时写入）；事实不被静默重写，页面重进读同一份',
  `stop_reason`            VARCHAR(32) NULL COMMENT '停止原因机器码（agent.core.StopReason 名）；未识别值前端原样显示',
  `error_code`             VARCHAR(64) NULL COMMENT '稳定机器码（如 UNKNOWN_CHARGE / CREDENTIAL_MISSING）；不解析文案分支',
  `estimated_cost`         DECIMAL(20,10) NULL COMMENT '保守估算额（发送前）；NULL 表示当时无法核算，绝不写 0 冒充免费',
  `settled_cost`           DECIMAL(20,10) NULL COMMENT '已结算实际费用之和；NULL 表示尚无任何已核算调用',
  `uncertain_cost`         DECIMAL(20,10) NULL COMMENT '已发出但用量不明的估算之和；不归零、不重发（设计 §6.2）',
  `cancel_requested_at`    DATETIME(3) NULL COMMENT 'UTC 用户请求取消时刻；幂等取消只写第一次',
  `worker_id`              VARCHAR(64) NULL COMMENT '领取本 run 的 Worker 标识；条件更新写入，恢复判定据此识别失联',
  `lease_expires_at`       DATETIME(3) NULL COMMENT 'UTC 租约到期时刻；心跳续租。过期且非终态 = 失去 Worker',
  `started_at`             DATETIME(3) NULL COMMENT 'UTC 真正开始执行时刻（QUEUED→RUNNING）',
  `finished_at`            DATETIME(3) NULL COMMENT 'UTC 终态时刻；非终态恒为 NULL',
  `lock_version`           INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；状态转移用条件更新',
  `created_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  -- 会话内活动 run 的唯一闸门（生成列 + UNIQUE，见文件头说明）
  UNIQUE KEY `uq_agent_run_1` (`active_conversation_id`),
  -- 幂等：同一用户的同一个键只对应一个 run
  UNIQUE KEY `uq_agent_run_2` (`user_id`, `idempotency_key`),
  UNIQUE KEY `uq_agent_run_3` (`run_uuid`),
  -- 子表（model_call / agent_step / chat_message）复合外键的目标
  UNIQUE KEY `uq_agent_run_4` (`id`, `user_id`),
  -- 会话历史按时间倒序；run 列表与"该会话是否有活动 run"的读取形态
  KEY `idx_agent_run_5` (`user_id`, `conversation_id`, `created_at`),
  -- 启动恢复扫描：非终态且租约过期
  KEY `idx_agent_run_6` (`status`, `lease_expires_at`),
  CONSTRAINT `ck_agent_run_0` CHECK (`credential_version` IS NULL OR `credential_version` > 0),
  -- 非终态不得自称已结束；终态与"仍在排队/执行"由同一列判定，不留两套口径
  CONSTRAINT `ck_agent_run_1` CHECK (`status` IN ('QUEUED','RUNNING') OR `finished_at` IS NOT NULL),
  CONSTRAINT `ck_agent_run_2` CHECK (`status` NOT IN ('QUEUED','RUNNING') OR `finished_at` IS NULL),
  CONSTRAINT `ck_agent_run_3` CHECK (`estimated_cost` IS NULL OR `estimated_cost` >= 0),
  CONSTRAINT `ck_agent_run_4` CHECK (`settled_cost` IS NULL OR `settled_cost` >= 0),
  CONSTRAINT `ck_agent_run_5` CHECK (`uncertain_cost` IS NULL OR `uncertain_cost` >= 0),
  CONSTRAINT `fk_agent_run_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_agent_run_1` FOREIGN KEY (`conversation_id`, `user_id`) REFERENCES `chat_conversation` (`id`, `user_id`),
  CONSTRAINT `fk_agent_run_2` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`),
  CONSTRAINT `fk_agent_run_3` FOREIGN KEY (`credential_id`, `user_id`) REFERENCES `model_credential` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次提问的后台执行任务';

-- -----------------------------------------------------------------------------
-- >>> V20__create_model_call.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V20：model_call（逐次远程调用的台账）
-- 依赖 V11、V12、V17、V19。对应设计 §7 的 model_call 与 §6.3 的闸门时序。
--
-- 状态机就是"请求到底可能已经发出去了没有"的唯一口径（设计 §5 第 6 条、§10）：
--   PREPARED  已过预算闸门并落台账，请求尚未交给传输层
--   SENT      已交给传输层（此后任何失败都可能已计费）
--   SUCCEEDED 拿到可解析的响应
--   UNKNOWN   已送达但结果不可读（读超时 / 断连 / 无法理解的响应体）
--   FAILED    可证明从未送达（连接建立前失败）或供应商明确拒绝
-- run 的恢复判定只读这张表：存在 PREPARED/SENT/UNKNOWN 即"可能有已发送的调用"→
-- run 转 FAILED + UNKNOWN_CHARGE，预占留在 UNCERTAIN，**绝不自动重发**；
-- 一张都没有才允许把 run 退回 QUEUED 重新领取（§10）。
--
-- UNIQUE(reservation_id) 是"一次预占只结算一次"的数据库防线（设计 §6.3 结算幂等）：
-- 同一笔预占想挂两次调用，第二条 INSERT 就撞索引，不需要应用记得查。
--
-- usage_payload 存供应商 usage 原文，四个 token 分项是它的结构化视图：
--   缺分项填 NULL，**不填 0**（协议 §6：0 会让一次用量不明的调用看起来免费）；
--   reasoning_tokens 是 completion 的细分项，只登记不再计价（§6.2）。
-- calculated_cost / currency 为本次核算金额与币种；币种与 run 的预算币种必须同源才可比。
--
-- credential_version 冗余自 run：事后追溯"哪一次调用用的是哪一把 Key 的版本"，不含任何材料。
-- provider_request_id 是供应商回传的标识（本应用没有外键指向它），只用于对账排查。
-- =============================================================================

CREATE TABLE `model_call` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`             BIGINT UNSIGNED NOT NULL COMMENT '所属用户；与 run 同属，所有查询双条件限定',
  `run_id`              BIGINT UNSIGNED NOT NULL COMMENT '所属 run；FK → agent_run(id, user_id)',
  `reservation_id`      BIGINT UNSIGNED NULL COMMENT '对应预占台账；FK → budget_reservation(id, user_id)；UNIQUE 保证一次预占只结算一次',
  `attempt_no`          INT UNSIGNED NOT NULL COMMENT '该 run 内第几次远程调用（从 1 起）；与预占的 attempt_no 同口径',
  `model_id`            BIGINT UNSIGNED NOT NULL COMMENT '实际使用的模型目录行；FK → model_catalog.id',
  `price_id`            BIGINT UNSIGNED NULL COMMENT '核算所用的 model_price 行（版本化报价快照）；FK → model_price.id；未核算时为 NULL',
  `credential_version`  INT UNSIGNED NULL COMMENT '本次调用绑定的凭据版本；不含任何密钥材料',
  `provider_request_id` VARCHAR(128) NULL COMMENT '供应商回带的请求标识，仅用于对账排查',
  `status`              ENUM('PREPARED','SENT','SUCCEEDED','UNKNOWN','FAILED') NOT NULL DEFAULT 'PREPARED' COMMENT '调用状态机；UNKNOWN 与 FAILED 的区别就是"是否可能已计费"',
  `usage_payload`       JSON NULL COMMENT '供应商 usage 原文（含未识别字段也照存）；NULL = 没有回报任何用量',
  `prompt_cache_hit_tokens`     INT UNSIGNED NULL COMMENT '缓存命中输入 token；NULL 表示未回报（不是 0）',
  `prompt_cache_miss_tokens`    INT UNSIGNED NULL COMMENT '未命中输入 token；NULL 表示未回报（不是 0）',
  `completion_tokens`           INT UNSIGNED NULL COMMENT '输出 token（含 reasoning 细分）',
  `reasoning_tokens`            INT UNSIGNED NULL COMMENT '思考 token：completion 的细分项，不参与计价',
  `calculated_cost`     DECIMAL(20,10) NULL COMMENT '本次核算金额；NULL = 不可核算，绝不写 0',
  `currency`            CHAR(3) NULL COMMENT '金额币种（ISO-4217 大写）；跨币种永不相减',
  `sent_at`             DATETIME(3) NULL COMMENT 'UTC 交给传输层的时刻；非 NULL 即"可能已送达"',
  `finished_at`         DATETIME(3) NULL COMMENT 'UTC 本次调用得到结论的时刻',
  `created_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_call_1` (`run_id`, `attempt_no`),
  -- 一笔预占只能挂一次调用（结算幂等的数据库防线）
  UNIQUE KEY `uq_model_call_2` (`reservation_id`),
  UNIQUE KEY `uq_model_call_3` (`id`, `user_id`),
  -- run 的"是否存在可能已发送的调用"扫描（恢复判定的唯一读取形态）
  KEY `idx_model_call_4` (`user_id`, `run_id`, `status`),
  CONSTRAINT `ck_model_call_0` CHECK (`attempt_no` > 0),
  CONSTRAINT `ck_model_call_1` CHECK (`calculated_cost` IS NULL OR `calculated_cost` >= 0),
  -- SENT/SUCCEEDED/UNKNOWN 都意味着"已经交给传输层"，没有 sent_at 就无从证明
  CONSTRAINT `ck_model_call_2` CHECK (`status` NOT IN ('SENT','SUCCEEDED','UNKNOWN') OR `sent_at` IS NOT NULL),
  CONSTRAINT `fk_model_call_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_model_call_1` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_model_call_2` FOREIGN KEY (`reservation_id`, `user_id`) REFERENCES `budget_reservation` (`id`, `user_id`),
  CONSTRAINT `fk_model_call_3` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`),
  CONSTRAINT `fk_model_call_4` FOREIGN KEY (`price_id`) REFERENCES `model_price` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐次远程模型调用台账';

-- -----------------------------------------------------------------------------
-- >>> V21__create_agent_step.sql
-- -----------------------------------------------------------------------------

-- =============================================================================
-- FitAgent agent-v1 迁移 V21：agent_step（run 的脱敏操作轨迹）
-- 依赖 V19、V20。对应设计 §7 的 agent_step 与 §11.1 规范化决定第 6 条。
--
-- 这张表**不存**任何东西足以还原模型隐藏推理或密钥材料，且刻意向这个方向收窄：
--   1. 没有 reasoning_content 列——本轮供应商返回的思考文本不落地、不外发（§11.1 第 6 条）；
--   2. `arguments_redacted` 只存 TraceRedaction 处理过的参数骨架：值里的凭据形态已被替换，
--      用户自由文本不进这里（问题原文已经在 chat_message 里，一处存就够）；
--   3. `result_summary` 是"几个来源、是否完整"这类工程摘要，**不是**工具返回的原始明细，
--      更不是回答正文——完整结果只存 agent_run.result_payload，页面绝不拿这里的截断文本冒充答案。
--
-- 轨迹是**可丢的诊断材料，不是计费依据**（设计 §10）：
-- 写这里的失败绝不触发模型调用重发，因此本表与 model_call 之间没有任何外键强绑定的写入顺序要求，
-- model_call_id 只是"这一步对应哪次远程调用"的可选关联。
--
-- UNIQUE(run_id, step_no)：step_no 由 TraceRecorder 在 run 内单调分配（从 1 起），
-- 重放同一个 run 时撞键即丢弃，不会产生第二条同序号轨迹。
-- =============================================================================

CREATE TABLE `agent_step` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '所属用户；与 run 同属，查询双条件限定',
  `run_id`             BIGINT UNSIGNED NOT NULL COMMENT '所属 run；FK → agent_run(id, user_id)',
  `step_no`            INT UNSIGNED NOT NULL COMMENT 'run 内单调步骤号（从 1 起，由 TraceRecorder 分配）',
  `model_call_id`      BIGINT UNSIGNED NULL COMMENT '关联的远程调用；FK → model_call(id, user_id)；工具步骤为 NULL',
  `event_type`         VARCHAR(32) NOT NULL COMMENT '事件类型字面值（MODEL_CALL/TOOL_CALL/TOOL_RESULT/CONTEXT_TRUNCATED/CITATION_REJECTED/ANSWER/RUN_STOPPED）',
  `tool_name`          VARCHAR(128) NULL COMMENT '工具名；非工具步骤为 NULL',
  `tool_call_id`       VARCHAR(128) NULL COMMENT '供应商给出的 tool_call_id 原值（协议配对排查用，不含个人数据）',
  `arguments_redacted` JSON NULL COMMENT '脱敏后的参数骨架；不含凭据形态的值，也不含用户自由文本',
  `result_summary`     VARCHAR(500) NULL COMMENT '工程摘要（几个来源/是否完整/调用次数）；**不是**回答正文，界面与恢复都不依赖它',
  `status`             VARCHAR(16) NOT NULL COMMENT '步骤状态：SUCCEEDED / FAILED / DECLARED（仅声明未执行）；界面按它上色，不解析 summary',
  `duration_ms`        BIGINT UNSIGNED NULL COMMENT '该步耗时毫秒；未执行为 NULL（不是 0）',
  `error_code`         VARCHAR(64) NULL COMMENT '稳定机器码（工具错误码或停止原因）；无错误为 NULL',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_agent_step_1` (`run_id`, `step_no`),
  UNIQUE KEY `uq_agent_step_2` (`id`, `user_id`),
  -- 进度摘要读取形态：一个 run 的全部步骤（按 step_no 升序）
  KEY `idx_agent_step_3` (`user_id`, `run_id`, `step_no`),
  CONSTRAINT `ck_agent_step_0` CHECK (`step_no` > 0),
  CONSTRAINT `fk_agent_step_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_agent_step_1` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_agent_step_2` FOREIGN KEY (`model_call_id`, `user_id`) REFERENCES `model_call` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='run 的脱敏操作轨迹（不含隐藏推理与密钥材料）';

-- -----------------------------------------------------------------------------
-- >>> V22__create_chat_message.sql
-- -----------------------------------------------------------------------------

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
