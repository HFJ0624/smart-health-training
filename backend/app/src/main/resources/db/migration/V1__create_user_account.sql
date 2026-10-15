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
