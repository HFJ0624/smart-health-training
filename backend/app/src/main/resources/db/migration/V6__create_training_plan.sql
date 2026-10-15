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
