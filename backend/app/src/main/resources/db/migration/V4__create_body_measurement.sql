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
