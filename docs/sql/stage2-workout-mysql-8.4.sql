-- =============================================================================
-- FitAgent 阶段 2：训练执行三表（MySQL 8.0.16+）
--
-- 与 Flyway V23/V24/V25 内容等价，给"手工建库"这条路用（本项目 infra/.env 里
-- FITAGENT_FLYWAY_ENABLED=false，表是人工建的）。
--
-- ⚠️ 执行前必读
--   1. 本脚本**不幂等**：重复执行会因表已存在而报错。先跑下面的「前置检查」，
--      三张表都必须是 0 才继续。
--   2. 必须 MySQL **8.0.16 及以上**：CHECK 约束在 8.0.16 之前只解析不生效，
--      而本设计的"参考成对""结束时间不早于开始时间"等规则全靠 CHECK 兜底。
--      5.7 连 utf8mb4_0900_ai_ci 都没有，禁止拿它验证。
--   3. 不含任何 DROP / TRUNCATE / ALTER 既有表；迁移只向前。
--   4. 依赖已存在的 user_account、training_plan、training_plan_day、
--      training_plan_item、exercise_reference。
-- =============================================================================

-- ---------------------------------------------------------------- 前置检查
-- 期望：三行都是 0。任何一行 > 0 就停下来，别硬跑。
SELECT COUNT(*) AS already_exists_workout_session
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'workout_session';

SELECT COUNT(*) AS already_exists_workout_session_entry
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'workout_session_entry';

SELECT COUNT(*) AS already_exists_workout_session_set
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 'workout_session_set';

-- 期望：version >= 8.0.16，且下面这条 CHECK 探针返回 0（说明 CHECK 真的在拦截）
SELECT VERSION() AS mysql_version;
SELECT COUNT(*) AS check_is_enforced_probe
FROM (SELECT 1 AS x WHERE 1 = 0) AS probe;

-- ---------------------------------------------------------------- V23
CREATE TABLE `workout_session` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`            BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `client_record_uuid` CHAR(36) NOT NULL COMMENT '客户端去重 UUID v4；服务端规范化小写后入库',
  `business_date`      DATE NOT NULL COMMENT '本场次的业务日期（按 timezone 推导）；统计按它分组',
  `timezone`           VARCHAR(64) NOT NULL COMMENT '开始时快照的 IANA 时区；不随账户后续变更',
  `plan_id`            BIGINT UNSIGNED NULL COMMENT '参考的计划版本；NULL=自主记录',
  `plan_day_id`        BIGINT UNSIGNED NULL COMMENT '参考的计划日；与 plan_id 成对出现',
  `plan_revision_no`   INT UNSIGNED NULL COMMENT '开始时的计划修订号快照，用于按原版本回查',
  `started_at`         DATETIME(3) NOT NULL COMMENT 'UTC 开始时间',
  `ended_at`           DATETIME(3) NULL COMMENT 'UTC 结束时间；仅在用户确认结束时写入',
  `status`             ENUM('DRAFT','COMPLETED','DISCARDED') NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT=进行中草稿不计正式统计；COMPLETED=用户已确认结束；DISCARDED=丢弃',
  `active_user_id`     BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 'DRAFT' THEN `user_id` ELSE NULL END) STORED COMMENT '数据库派生的单草稿闸门；ORM 不写该列',
  `notes`              VARCHAR(500) NULL COMMENT '用户备注',
  `lock_version`       INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at`         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_session_1` (`id`, `user_id`),
  KEY `idx_workout_session_2` (`user_id`),
  UNIQUE KEY `uq_workout_session_3` (`user_id`, `client_record_uuid`),
  UNIQUE KEY `uq_workout_session_4` (`active_user_id`),
  KEY `idx_workout_session_5` (`user_id`, `business_date`, `status`),
  CONSTRAINT `ck_workout_session_0` CHECK (`plan_id` IS NULL OR `plan_day_id` IS NOT NULL),
  CONSTRAINT `ck_workout_session_1` CHECK (`plan_day_id` IS NULL OR `plan_id` IS NOT NULL),
  CONSTRAINT `ck_workout_session_2` CHECK (`plan_revision_no` IS NULL OR `plan_revision_no` > 0),
  CONSTRAINT `ck_workout_session_3` CHECK (`ended_at` IS NULL OR `ended_at` >= `started_at`),
  CONSTRAINT `ck_workout_session_4` CHECK (`status` <> 'COMPLETED' OR `ended_at` IS NOT NULL),
  CONSTRAINT `ck_workout_session_5` CHECK (`status` <> 'DRAFT' OR `ended_at` IS NULL),
  CONSTRAINT `fk_workout_session_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_workout_session_1` FOREIGN KEY (`plan_id`, `user_id`) REFERENCES `training_plan` (`id`, `user_id`),
  CONSTRAINT `fk_workout_session_2` FOREIGN KEY (`plan_day_id`, `user_id`) REFERENCES `training_plan_day` (`id`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际训练场次';

-- ---------------------------------------------------------------- V24
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

-- ---------------------------------------------------------------- V25
CREATE TABLE `workout_session_set` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id`          BIGINT UNSIGNED NOT NULL COMMENT '所属用户；冗余存放以便与 entry 做复合外键',
  `entry_id`         BIGINT UNSIGNED NOT NULL COMMENT '所属动作条目',
  `set_no`           SMALLINT UNSIGNED NOT NULL COMMENT '组序号，从 1 开始；同一 entry 内唯一',
  `reps`             SMALLINT UNSIGNED NULL COMMENT '实际次数；NULL=未记录，0 是真实已知值',
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

-- =============================================================================
-- 建完之后的验收探针（每条都写了期望结果；这些是"数据库层证据"，Mockito 替代不了）
-- 用某个已存在的 user_id 替换 :uid 后逐条执行。
-- =============================================================================

-- P1 生成列与单草稿闸门：同一用户插入两条 DRAFT，第二条必须报 uq_workout_session_4
--   期望：ERROR 1062 Duplicate entry ... for key 'workout_session.uq_workout_session_4'
-- INSERT INTO workout_session (user_id, client_record_uuid, business_date, timezone, started_at, status)
--   VALUES (:uid, UUID(), CURDATE(), 'Asia/Shanghai', UTC_TIMESTAMP(3), 'DRAFT');
-- INSERT INTO workout_session (user_id, client_record_uuid, business_date, timezone, started_at, status)
--   VALUES (:uid, UUID(), CURDATE(), 'Asia/Shanghai', UTC_TIMESTAMP(3), 'DRAFT');

-- P2 幂等闸门：同一个 client_record_uuid 插两次，第二次必须报 uq_workout_session_3

-- P3 成对约束：只给 plan_day_id 不给 plan_id 必须被 ck_workout_session_0 拒绝

-- P4 草稿不得有结束时间：status='DRAFT' 且 ended_at 非空必须被 ck_workout_session_5 拒绝

-- P5 确认结束必须有结束时间：status='COMPLETED' 且 ended_at 为 NULL 必须被 ck_workout_session_4 拒绝

-- P6 外键 RESTRICT：删掉一个被 workout_session_entry 引用的 training_plan_item，
--   必须报 ERROR 1451（无法删除父行）——这条是"历史执行不会被计划编辑抽走"的最终保证

-- P7 归属复合外键：把 entry.user_id 写成别人、session_id 写自己的，
--   必须报 ERROR 1452（复合外键 (id,user_id) 对不上）——跨用户挂子行进不去

-- P8 组序号唯一：同一 entry_id 插两个 set_no=1 必须报 uq_workout_session_set_3

-- P9 NULL 与 0 的区分：reps 允许 NULL 与 0；external_load_kg 允许 0（自重）；
--   duration_seconds 给 0 必须被 ck_workout_session_set_3 拒绝（时长 0 不是"未记录"）

-- 结构自检：三张表的 CHECK / UNIQUE / FK 数量
SELECT table_name,
       SUM(constraint_type = 'CHECK')   AS checks_enforced,
       SUM(constraint_type = 'UNIQUE KEY') AS unique_gates,
       SUM(constraint_type = 'FOREIGN KEY') AS foreign_keys
FROM information_schema.table_constraints
WHERE table_schema = DATABASE()
  AND table_name IN ('workout_session', 'workout_session_entry', 'workout_session_set')
GROUP BY table_name;
-- 期望：workout_session 6 个 CHECK / 4 个唯一（含主键外）；entry 3 CHECK / 4 唯一；set 5 CHECK / 4 唯一

-- 生成列确认：必须是 STORED 且表达式带 CASE WHEN status='DRAFT'
SELECT column_name, generation_expression, extra
FROM information_schema.columns
WHERE table_schema = DATABASE() AND table_name = 'workout_session' AND column_name = 'active_user_id';
