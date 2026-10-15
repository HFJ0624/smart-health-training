-- FitAgent 设计 v1.0；2026-10-12；目标 MySQL 8.4（CHECK 至少需要 8.0.16）。
-- 仅在新数据库初始化；不含 DROP/TRUNCATE，不关闭外键。重复导入会报已存在，请用迁移管理后续变更。
-- Key/微信 AppSecret/主加密密钥不能写入本脚本；临时媒体不作为长期服务器文件存储。
CREATE DATABASE IF NOT EXISTS `fitagent` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE `fitagent`;
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- P1 微信用户与数据修订号
CREATE TABLE `user_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `wechat_app_id` VARCHAR(64) NOT NULL COMMENT '微信小程序 AppID',
  `wechat_open_id` VARCHAR(128) NOT NULL COMMENT '后端 code2Session 获取的 openid',
  `wechat_union_id` VARCHAR(128) NULL COMMENT '可选 unionid',
  `nickname` VARCHAR(80) NULL COMMENT '用户显示名称',
  `role` ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT '应用角色',
  `status` ENUM('ACTIVE','DISABLED','DELETION_PENDING') NOT NULL DEFAULT 'ACTIVE' COMMENT '账户状态',
  `timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '记录所用业务时区',
  `data_revision` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '饮食、身体、训练等数据变更时事务内递增',
  `last_login_at` DATETIME(3) NULL COMMENT 'UTC 最近登录时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_account_1` (`wechat_app_id`, `wechat_open_id`)
) ENGINE=InnoDB COMMENT='微信用户与数据修订号';

-- P1 有来源的食物营养条目
CREATE TABLE `food_reference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `food_code` VARCHAR(80) NOT NULL COMMENT '内部唯一食物代码',
  `name` VARCHAR(160) NOT NULL COMMENT '食物名称',
  `aliases` JSON NULL COMMENT '别名及匹配关键词',
  `preparation` VARCHAR(160) NOT NULL COMMENT '生熟状态、烹饪方式与可食部说明',
  `energy_kcal_per_100g` DECIMAL(9,3) NOT NULL COMMENT '每 100g 可食部热量，kcal',
  `protein_g_per_100g` DECIMAL(7,3) NULL COMMENT '每 100g 蛋白质，g',
  `fat_g_per_100g` DECIMAL(7,3) NULL COMMENT '每 100g 脂肪，g',
  `carb_g_per_100g` DECIMAL(7,3) NULL COMMENT '每 100g 碳水，g',
  `source_name` VARCHAR(200) NOT NULL COMMENT '营养标签或数据源名称',
  `source_url` VARCHAR(1000) NULL COMMENT '来源链接',
  `source_record_code` VARCHAR(100) NULL COMMENT '源数据 ID',
  `source_version` VARCHAR(80) NOT NULL COMMENT '数据版本或核查日期',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '条目状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_food_reference_1` (`food_code`),
  KEY `idx_food_reference_2` (`name`),
  CONSTRAINT `ck_food_reference_0` CHECK (energy_kcal_per_100g >= 0),
  CONSTRAINT `ck_food_reference_1` CHECK (protein_g_per_100g IS NULL OR protein_g_per_100g >= 0),
  CONSTRAINT `ck_food_reference_2` CHECK (fat_g_per_100g IS NULL OR fat_g_per_100g >= 0),
  CONSTRAINT `ck_food_reference_3` CHECK (carb_g_per_100g IS NULL OR carb_g_per_100g >= 0)
) ENGINE=InnoDB COMMENT='有来源的食物营养条目';

-- P1 运动 MET 来源表
CREATE TABLE `met_activity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `activity_code` VARCHAR(16) NOT NULL COMMENT '源运动代码',
  `description` VARCHAR(300) NOT NULL COMMENT '活动及强度描述',
  `met_value` DECIMAL(6,2) NOT NULL COMMENT '来源公布的标准 MET 值',
  `population` VARCHAR(80) NOT NULL COMMENT '适用人群，如 ADULT_19_59',
  `source_name` VARCHAR(160) NOT NULL COMMENT '来源名称',
  `source_version` VARCHAR(80) NOT NULL COMMENT '来源版本',
  `source_url` VARCHAR(1000) NOT NULL COMMENT '可追溯来源',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '条目状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_met_activity_1` (`activity_code`, `source_version`),
  CONSTRAINT `ck_met_activity_0` CHECK (met_value > 0)
) ENGINE=InnoDB COMMENT='运动 MET 来源表';

-- P1 计算规则版本目录
CREATE TABLE `calculation_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '规则标识',
  `rule_version` VARCHAR(32) NOT NULL COMMENT '不可原地覆盖的规则版本',
  `display_name` VARCHAR(160) NOT NULL COMMENT '规则名称',
  `rule_kind` ENUM('SOURCE_FORMULA','ENGINEERING_METRIC') NOT NULL COMMENT '来源公式或项目定义指标',
  `expression_text` TEXT NOT NULL COMMENT '公式说明；不可 eval 执行',
  `parameters` JSON NOT NULL COMMENT '单位、系数、适用范围和缺失值策略',
  `source_urls` JSON NOT NULL COMMENT '来源链接数组；工程指标可为空数组',
  `source_checked_on` DATE NOT NULL COMMENT '来源核查日期',
  `status` ENUM('ACTIVE','RETIRED') NOT NULL DEFAULT 'ACTIVE' COMMENT '版本状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_calculation_rule_1` (`rule_code`, `rule_version`)
) ENGINE=InnoDB COMMENT='计算规则版本目录';

-- P1 训练动作字典
CREATE TABLE `exercise_reference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `exercise_code` VARCHAR(64) NOT NULL COMMENT '动作唯一代码',
  `name` VARCHAR(120) NOT NULL COMMENT '动作名称',
  `category` ENUM('STRENGTH','CARDIO','MOBILITY') NOT NULL COMMENT '动作类型',
  `muscle_groups` JSON NULL COMMENT '相关肌群',
  `equipment` JSON NULL COMMENT '所需器械',
  `instructions` TEXT NULL COMMENT '经维护的基础操作说明',
  `source_url` VARCHAR(1000) NULL COMMENT '说明来源',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_exercise_reference_1` (`exercise_code`)
) ENGINE=InnoDB COMMENT='训练动作字典';

-- P2 供应商目录
CREATE TABLE `model_provider` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `provider_code` VARCHAR(40) NOT NULL COMMENT '供应商唯一代码',
  `display_name` VARCHAR(100) NOT NULL COMMENT '显示名称',
  `base_url` VARCHAR(500) NOT NULL COMMENT '后端白名单地址；不接受模型或用户传入任意地址',
  `balance_supported` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '适配器是否支持官方余额接口',
  `adapter_code` VARCHAR(80) NOT NULL COMMENT '注册的 Java 适配器 ID',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '供应商状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_provider_1` (`provider_code`)
) ENGINE=InnoDB COMMENT='供应商目录';

-- P3 跨 MySQL 与向量索引的最终一致性事件
CREATE TABLE `outbox_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `event_uuid` CHAR(36) NOT NULL COMMENT '事件幂等 UUID',
  `aggregate_type` VARCHAR(40) NOT NULL COMMENT '如 KNOWLEDGE_DOCUMENT / MEDIA_ASSET',
  `aggregate_id` BIGINT UNSIGNED NOT NULL COMMENT '逻辑实体 ID；删除后仍可执行清理',
  `user_id_hint` BIGINT UNSIGNED NULL COMMENT '清理定位提示；不是授权依据，也不设外键避免删除事件丢失',
  `event_type` ENUM('INDEX_DOCUMENT','DELETE_VECTORS','CLEAN_TEMP_MEDIA') NOT NULL COMMENT '事件种类',
  `payload` JSON NOT NULL COMMENT '最小必要的文档版本、向量 ID 或清理定位信息；无 Key/原件',
  `status` ENUM('PENDING','RUNNING','DONE','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '状态',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '尝试次数',
  `retry_at` DATETIME(3) NULL COMMENT '可重试时间',
  `lease_expires_at` DATETIME(3) NULL COMMENT 'worker 租约',
  `last_error_code` VARCHAR(64) NULL COMMENT '脱敏错误码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_outbox_event_1` (`event_uuid`),
  KEY `idx_outbox_event_2` (`status`, `retry_at`)
) ENGINE=InnoDB COMMENT='跨 MySQL 与向量索引的最终一致性事件';

-- P1 身体与训练背景
CREATE TABLE `user_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `birth_date` DATE NULL COMMENT '可选出生日期；不提供时年龄相关公式不执行',
  `sex_for_equation` ENUM('MALE','FEMALE','UNSPECIFIED') NOT NULL DEFAULT 'UNSPECIFIED' COMMENT '公式所需生理性别参数；未指定则不套用性别公式',
  `height_cm` DECIMAL(6,2) NULL COMMENT '身高，cm',
  `training_level` ENUM('BEGINNER','INTERMEDIATE','ADVANCED') NOT NULL DEFAULT 'BEGINNER' COMMENT '自报训练经验',
  `activity_category` ENUM('LIGHT','MODERATE','VIGOROUS') NULL COMMENT '已确认的长期活动类别；含工作、日常及习惯运动',
  `activity_confirmed_at` DATETIME(3) NULL COMMENT '长期活动类别最近确认时间',
  `diet_preferences` JSON NULL COMMENT '饮食偏好及用户主动提供的限制',
  `equipment` JSON NULL COMMENT '可用器械列表',
  `training_constraints` JSON NULL COMMENT '可用时间和用户主动提供的训练限制',
  `professional_guidance_required` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户自报需专业指导；不保存诊断推断',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_profile_1` (`id`, `user_id`),
  KEY `idx_user_profile_2` (`user_id`),
  UNIQUE KEY `uq_user_profile_3` (`user_id`),
  CONSTRAINT `ck_user_profile_0` CHECK (height_cm IS NULL OR height_cm > 0),
  CONSTRAINT `fk_user_profile_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='身体与训练背景';

-- P1 目标历史
CREATE TABLE `user_goal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `goal_type` ENUM('FAT_LOSS','MUSCLE_GAIN','MAINTAIN','GENERAL_FITNESS') NOT NULL COMMENT '目标类型',
  `target_weight_kg` DECIMAL(6,2) NULL COMMENT '用户目标体重；kg',
  `daily_intake_target_kcal` DECIMAL(9,2) NULL COMMENT '用户确认的饮食目标；不是系统自动处方',
  `weekly_sessions_target` TINYINT UNSIGNED NULL COMMENT '每周目标训练次数',
  `starts_on` DATE NOT NULL COMMENT '目标开始日期',
  `ends_on` DATE NULL COMMENT '可选结束日期',
  `status` ENUM('DRAFT','ACTIVE','ARCHIVED') NOT NULL DEFAULT 'DRAFT' COMMENT '目标状态',
  `active_user_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN user_id ELSE NULL END) STORED COMMENT '数据库派生字段；每用户最多一个 ACTIVE 目标',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_user_goal_1` (`id`, `user_id`),
  KEY `idx_user_goal_2` (`user_id`),
  UNIQUE KEY `uq_user_goal_3` (`active_user_id`),
  CONSTRAINT `ck_user_goal_0` CHECK (target_weight_kg IS NULL OR target_weight_kg > 0),
  CONSTRAINT `ck_user_goal_1` CHECK (daily_intake_target_kcal IS NULL OR daily_intake_target_kcal > 0),
  CONSTRAINT `ck_user_goal_2` CHECK (ends_on IS NULL OR ends_on >= starts_on),
  CONSTRAINT `fk_user_goal_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='目标历史';

-- P1 身体记录
CREATE TABLE `body_measurement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `measured_at` DATETIME(3) NOT NULL COMMENT 'UTC 测量时间',
  `business_date` DATE NOT NULL COMMENT '按记录时用户时区确定的业务日期',
  `weight_kg` DECIMAL(6,2) NOT NULL COMMENT '用户实测体重，kg',
  `waist_cm` DECIMAL(6,2) NULL COMMENT '可选腰围，cm',
  `body_fat_percent` DECIMAL(5,2) NULL COMMENT '可选自报体脂率；不通过照片推算',
  `source` ENUM('MANUAL','IMPORT') NOT NULL DEFAULT 'MANUAL' COMMENT '记录来源',
  `client_record_uuid` CHAR(36) NOT NULL COMMENT '客户端去重 UUID',
  `notes` VARCHAR(500) NULL COMMENT '备注',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_body_measurement_1` (`id`, `user_id`),
  KEY `idx_body_measurement_2` (`user_id`),
  UNIQUE KEY `uq_body_measurement_3` (`user_id`, `client_record_uuid`),
  KEY `idx_body_measurement_4` (`user_id`, `business_date`, `measured_at`),
  CONSTRAINT `ck_body_measurement_0` CHECK (weight_kg > 0),
  CONSTRAINT `ck_body_measurement_1` CHECK (waist_cm IS NULL OR waist_cm > 0),
  CONSTRAINT `ck_body_measurement_2` CHECK (body_fat_percent IS NULL OR (body_fat_percent >= 0 AND body_fat_percent <= 100)),
  CONSTRAINT `fk_body_measurement_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='身体记录';

-- P1 每日记录完整性与用户自报恢复信息
CREATE TABLE `daily_checkin` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `business_date` DATE NOT NULL COMMENT '按用户记录时区确定的业务日期',
  `intake_log_complete` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户明确确认当天饮食已全部记录；不能从餐次数自动推断',
  `workout_log_complete` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户明确确认当天训练已记录完整',
  `sleep_hours` DECIMAL(4,2) NULL COMMENT '可选自报睡眠小时数',
  `recovery_note` VARCHAR(500) NULL COMMENT '可选恢复与疲劳感受',
  `completed_at` DATETIME(3) NULL COMMENT '最近确认记录完整性的 UTC 时间',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_daily_checkin_1` (`id`, `user_id`),
  KEY `idx_daily_checkin_2` (`user_id`),
  UNIQUE KEY `uq_daily_checkin_3` (`user_id`, `business_date`),
  CONSTRAINT `ck_daily_checkin_0` CHECK (sleep_hours IS NULL OR (sleep_hours >= 0 AND sleep_hours <= 24)),
  CONSTRAINT `fk_daily_checkin_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='每日记录完整性与用户自报恢复信息';

-- P2 模型能力配置
CREATE TABLE `model_catalog` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '供应商',
  `model_code` VARCHAR(100) NOT NULL COMMENT '官方调用模型名',
  `display_name` VARCHAR(120) NOT NULL COMMENT '显示名',
  `supports_vision` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持图片',
  `supports_tools` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持工具调用',
  `supports_json` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否支持 JSON 模式',
  `capability_snapshot` JSON NOT NULL COMMENT '接口形状及能力核查元数据',
  `source_checked_on` DATE NOT NULL COMMENT '能力核查日期',
  `status` ENUM('ACTIVE','DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '模型状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_catalog_1` (`provider_id`, `model_code`),
  CONSTRAINT `fk_model_catalog_0` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`)
) ENGINE=InnoDB COMMENT='模型能力配置';

-- P2 用户模型 Key 加密凭据
CREATE TABLE `model_credential` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '供应商',
  `label` VARCHAR(80) NOT NULL COMMENT '凭据别名',
  `key_suffix` VARCHAR(8) NOT NULL COMMENT '只允许展示的尾部片段',
  `key_fingerprint` BINARY(32) NOT NULL COMMENT '服务端 HMAC 指纹；不用可逆明文做去重',
  `ciphertext` BLOB NULL COMMENT 'AES-GCM 密文及认证标签；撤销后清除，不得返回前端',
  `nonce` BINARY(12) NULL COMMENT '每次加密生成的新随机 nonce；撤销后可清除',
  `encryption_key_version` VARCHAR(40) NOT NULL COMMENT '主加密密钥版本；主密钥不入库',
  `credential_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '换 Key 时递增；账本保存当时版本',
  `status` ENUM('ACTIVE','INVALID','REVOKED') NOT NULL DEFAULT 'ACTIVE' COMMENT '凭据状态',
  `validated_at` DATETIME(3) NULL COMMENT '最近验证成功时间',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销时间',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_credential_1` (`id`, `user_id`),
  KEY `idx_model_credential_2` (`user_id`),
  UNIQUE KEY `uq_model_credential_3` (`user_id`, `provider_id`),
  CONSTRAINT `ck_model_credential_0` CHECK (status <> 'ACTIVE' OR (ciphertext IS NOT NULL AND nonce IS NOT NULL)),
  CONSTRAINT `fk_model_credential_0` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`),
  CONSTRAINT `fk_model_credential_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='用户模型 Key 加密凭据';

-- P2 应用侧费用预算
CREATE TABLE `budget_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '供应商',
  `currency` CHAR(3) NOT NULL COMMENT '预算币种；必须与价格核算币种一致',
  `max_task_cost` DECIMAL(20,10) NULL COMMENT '单次完整任务预算；NULL 未设限',
  `max_daily_cost` DECIMAL(20,10) NULL COMMENT '本应用每日预算；NULL 未设限',
  `budget_timezone` VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '首版固定 Asia/Shanghai，避免移动时区绕过预算',
  `max_agent_rounds` TINYINT UNSIGNED NOT NULL DEFAULT 6 COMMENT '工具循环轮数上限',
  `max_output_tokens` INT UNSIGNED NOT NULL DEFAULT 2048 COMMENT '每次请求输出上限；平台另有硬上限',
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用配置的费用预算',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_budget_policy_1` (`id`, `user_id`),
  KEY `idx_budget_policy_2` (`user_id`),
  UNIQUE KEY `uq_budget_policy_3` (`user_id`, `provider_id`, `currency`),
  CONSTRAINT `ck_budget_policy_0` CHECK (max_task_cost IS NULL OR max_task_cost > 0),
  CONSTRAINT `ck_budget_policy_1` CHECK (max_daily_cost IS NULL OR max_daily_cost > 0),
  CONSTRAINT `ck_budget_policy_2` CHECK (max_agent_rounds > 0),
  CONSTRAINT `ck_budget_policy_3` CHECK (max_output_tokens > 0),
  CONSTRAINT `fk_budget_policy_0` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`),
  CONSTRAINT `fk_budget_policy_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='应用侧费用预算';

-- P2 每日预算并发汇总
CREATE TABLE `budget_daily_usage` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `provider_id` BIGINT UNSIGNED NOT NULL COMMENT '供应商',
  `currency` CHAR(3) NOT NULL COMMENT '同币种累计',
  `budget_date` DATE NOT NULL COMMENT 'Asia/Shanghai 预算日；按每次调用开始时间归属',
  `settled_cost` DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '已收到用量、按价格表核算的费用',
  `reserved_cost` DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '正在执行调用的预占金额',
  `uncertain_reserved_cost` DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '结果不明调用仍保留的预占金额',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_budget_daily_usage_1` (`id`, `user_id`),
  KEY `idx_budget_daily_usage_2` (`user_id`),
  UNIQUE KEY `uq_budget_daily_usage_3` (`user_id`, `provider_id`, `currency`, `budget_date`),
  CONSTRAINT `ck_budget_daily_usage_0` CHECK (settled_cost >= 0),
  CONSTRAINT `ck_budget_daily_usage_1` CHECK (reserved_cost >= 0),
  CONSTRAINT `ck_budget_daily_usage_2` CHECK (uncertain_reserved_cost >= 0),
  CONSTRAINT `fk_budget_daily_usage_0` FOREIGN KEY (`provider_id`) REFERENCES `model_provider` (`id`),
  CONSTRAINT `fk_budget_daily_usage_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='每日预算并发汇总';

-- P2 聊天会话
CREATE TABLE `chat_conversation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `title` VARCHAR(160) NOT NULL COMMENT '会话标题',
  `summary_text` TEXT NULL COMMENT '可选历史摘要；仅保存必要事实',
  `summary_through_message_id` BIGINT UNSIGNED NULL COMMENT '摘要覆盖到的消息 ID；不是跨租户引用',
  `status` ENUM('ACTIVE','ARCHIVED') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `last_message_at` DATETIME(3) NULL COMMENT 'UTC 最近消息时间',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_conversation_1` (`id`, `user_id`),
  KEY `idx_chat_conversation_2` (`user_id`),
  KEY `idx_chat_conversation_3` (`user_id`, `last_message_at`),
  CONSTRAINT `fk_chat_conversation_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='聊天会话';

-- P1 手机原件与服务器临时媒体元数据
CREATE TABLE `media_asset` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `asset_uuid` CHAR(36) NOT NULL COMMENT '手机与服务器共用逻辑 ID；手机实际路径仅在本机映射',
  `original_name` VARCHAR(255) NOT NULL COMMENT '原始文件名；不能作为服务器存储路径',
  `media_kind` ENUM('IMAGE','VIDEO','DOCUMENT') NOT NULL COMMENT '文件类型',
  `mime_type` VARCHAR(100) NOT NULL COMMENT '检测后的 MIME',
  `file_size_bytes` BIGINT UNSIGNED NOT NULL COMMENT '原件字节数',
  `sha256` BINARY(32) NULL COMMENT '上传后服务端验证内容摘要；用于重新关联本机原件',
  `duration_ms` BIGINT UNSIGNED NULL COMMENT '视频时长；由 ffprobe 核实',
  `storage_state` ENUM('LOCAL_ONLY','UPLOADING','TEMP_AVAILABLE','CLEANUP_PENDING','CLEANED','INVALID') NOT NULL DEFAULT 'LOCAL_ONLY' COMMENT '服务器文件状态',
  `temp_relative_path` VARCHAR(500) NULL COMMENT '仅临时阶段使用的服务端生成相对路径；清理后置 NULL',
  `uploaded_at` DATETIME(3) NULL COMMENT 'UTC 上传完成时间',
  `expires_at` DATETIME(3) NULL COMMENT '失败、取消或未完成上传的清理截止时间',
  `cleaned_at` DATETIME(3) NULL COMMENT '原件和中间文件全部清理时间',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_media_asset_1` (`id`, `user_id`),
  KEY `idx_media_asset_2` (`user_id`),
  UNIQUE KEY `uq_media_asset_3` (`user_id`, `asset_uuid`),
  KEY `idx_media_asset_4` (`storage_state`, `expires_at`),
  CONSTRAINT `ck_media_asset_0` CHECK (file_size_bytes > 0),
  CONSTRAINT `ck_media_asset_1` CHECK (duration_ms IS NULL OR duration_ms > 0),
  CONSTRAINT `fk_media_asset_0` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='手机原件与服务器临时媒体元数据';

-- P2 价格版本与币种
CREATE TABLE `model_price` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `model_id` BIGINT UNSIGNED NOT NULL COMMENT '模型',
  `price_version` VARCHAR(64) NOT NULL COMMENT '应用价格版本；历史调用引用不可覆盖',
  `currency` CHAR(3) NOT NULL COMMENT '原生报价币种，如 CNY、USD',
  `rate_tier` ENUM('PEAK','OFF_PEAK') NOT NULL COMMENT '对应价格档位',
  `input_hit_per_million` DECIMAL(20,10) NOT NULL COMMENT '每百万缓存命中输入 Token 价格',
  `input_miss_per_million` DECIMAL(20,10) NOT NULL COMMENT '每百万未命中输入 Token 价格',
  `output_per_million` DECIMAL(20,10) NOT NULL COMMENT '每百万输出 Token 价格',
  `schedule_policy` JSON NOT NULL COMMENT '档位判断规则、时区和节假日核查状态',
  `source_url` VARCHAR(1000) NOT NULL COMMENT '官方价格来源',
  `source_checked_on` DATE NOT NULL COMMENT '核查日期',
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '可供应用核算；上线前应重新核对',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_price_1` (`model_id`, `price_version`, `currency`, `rate_tier`),
  CONSTRAINT `ck_model_price_0` CHECK (input_hit_per_million >= 0),
  CONSTRAINT `ck_model_price_1` CHECK (input_miss_per_million >= 0),
  CONSTRAINT `ck_model_price_2` CHECK (output_per_million >= 0),
  CONSTRAINT `fk_model_price_0` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`)
) ENGINE=InnoDB COMMENT='价格版本与币种';

-- P2 供应商账户余额快照
CREATE TABLE `provider_balance_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `credential_id` BIGINT UNSIGNED NOT NULL COMMENT '使用的凭据',
  `credential_version` INT UNSIGNED NOT NULL COMMENT '当时凭据版本',
  `currency` CHAR(3) NOT NULL COMMENT '官方返回币种',
  `total_balance` DECIMAL(20,10) NOT NULL COMMENT '官方账户总可用余额',
  `granted_balance` DECIMAL(20,10) NOT NULL COMMENT '官方赠金',
  `topped_up_balance` DECIMAL(20,10) NOT NULL COMMENT '官方充值余额',
  `is_available` BOOLEAN NOT NULL COMMENT '官方返回是否可调用',
  `fetched_at` DATETIME(3) NOT NULL COMMENT 'UTC 查询成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_provider_balance_snapshot_1` (`id`, `user_id`),
  KEY `idx_provider_balance_snapshot_2` (`user_id`),
  KEY `idx_provider_balance_snapshot_3` (`user_id`, `credential_id`, `currency`, `fetched_at`),
  CONSTRAINT `fk_provider_balance_snapshot_0` FOREIGN KEY (`credential_id`, `user_id`) REFERENCES `model_credential` (`id`, `user_id`),
  CONSTRAINT `fk_provider_balance_snapshot_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='供应商账户余额快照';

-- P2 一次完整 AI 任务
CREATE TABLE `agent_run` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `run_uuid` CHAR(36) NOT NULL COMMENT '对外任务 UUID',
  `conversation_id` BIGINT UNSIGNED NULL COMMENT '聊天会话；固定工作流可为空',
  `credential_id` BIGINT UNSIGNED NOT NULL COMMENT '绑定当前用户有效凭据',
  `task_type` ENUM('COACH_CHAT','FOOD_RECOGNITION','PLAN_GENERATE','PLAN_IMPORT','DOCUMENT_PARSE','VIDEO_GUIDE','REPORT') NOT NULL COMMENT '任务种类',
  `idempotency_key` VARCHAR(100) NOT NULL COMMENT '客户端请求去重键',
  `status` ENUM('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','BUDGET_STOPPED','UNKNOWN','WAITING_CREDENTIAL') NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态',
  `currency` CHAR(3) NOT NULL COMMENT '本次核算币种',
  `estimated_cost` DECIMAL(20,10) NULL COMMENT '发送前展示的整次任务估算',
  `settled_cost` DECIMAL(20,10) NOT NULL DEFAULT 0 COMMENT '已知调用费用之和',
  `uncertain_cost` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否存在缺失用量或档位不明调用',
  `budget_snapshot` JSON NOT NULL COMMENT '启动时预算与调用限制快照',
  `context_revision` BIGINT UNSIGNED NOT NULL COMMENT '启动时用户数据修订号',
  `started_at` DATETIME(3) NULL COMMENT 'UTC 开始时间',
  `finished_at` DATETIME(3) NULL COMMENT 'UTC 结束时间',
  `error_code` VARCHAR(64) NULL COMMENT '脱敏错误码',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_agent_run_1` (`id`, `user_id`),
  KEY `idx_agent_run_2` (`user_id`),
  UNIQUE KEY `uq_agent_run_3` (`run_uuid`),
  UNIQUE KEY `uq_agent_run_4` (`user_id`, `idempotency_key`),
  KEY `idx_agent_run_5` (`user_id`, `created_at`),
  CONSTRAINT `ck_agent_run_0` CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
  CONSTRAINT `ck_agent_run_1` CHECK (settled_cost >= 0),
  CONSTRAINT `fk_agent_run_0` FOREIGN KEY (`conversation_id`, `user_id`) REFERENCES `chat_conversation` (`id`, `user_id`),
  CONSTRAINT `fk_agent_run_1` FOREIGN KEY (`credential_id`, `user_id`) REFERENCES `model_credential` (`id`, `user_id`),
  CONSTRAINT `fk_agent_run_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='一次完整 AI 任务';

-- P2 逐次模型调用预算预占
CREATE TABLE `budget_reservation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `run_id` BIGINT UNSIGNED NOT NULL COMMENT '完整任务',
  `daily_usage_id` BIGINT UNSIGNED NOT NULL COMMENT '对应调用开始日的预算汇总',
  `attempt_no` INT UNSIGNED NOT NULL COMMENT '本任务唯一调用尝试序号',
  `reserved_amount` DECIMAL(20,10) NOT NULL COMMENT '本次请求预占金额',
  `settled_amount` DECIMAL(20,10) NULL COMMENT '收到用量后的核算金额',
  `estimate_quality` ENUM('TOKENIZER','CONSERVATIVE_ESTIMATE') NOT NULL COMMENT '输入 Token 估计方法；两者都不代表供应商账单保证',
  `status` ENUM('HELD','SETTLED','RELEASED','UNKNOWN_HELD') NOT NULL DEFAULT 'HELD' COMMENT '预占状态',
  `settled_at` DATETIME(3) NULL COMMENT '结算时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_budget_reservation_1` (`id`, `user_id`),
  KEY `idx_budget_reservation_2` (`user_id`),
  UNIQUE KEY `uq_budget_reservation_3` (`run_id`, `attempt_no`),
  CONSTRAINT `ck_budget_reservation_0` CHECK (reserved_amount >= 0),
  CONSTRAINT `ck_budget_reservation_1` CHECK (settled_amount IS NULL OR settled_amount >= 0),
  CONSTRAINT `fk_budget_reservation_0` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_budget_reservation_1` FOREIGN KEY (`daily_usage_id`, `user_id`) REFERENCES `budget_daily_usage` (`id`, `user_id`),
  CONSTRAINT `fk_budget_reservation_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='逐次模型调用预算预占';

-- P2 聊天消息与工具消息
CREATE TABLE `chat_message` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT '所属会话',
  `run_id` BIGINT UNSIGNED NULL COMMENT '所属任务',
  `message_no` INT UNSIGNED NOT NULL COMMENT '会话内顺序；在会话行锁保护下分配',
  `role` ENUM('USER','ASSISTANT','TOOL') NOT NULL COMMENT '消息角色',
  `content_text` LONGTEXT NULL COMMENT '完整组装后的文本；不保存隐藏推理过程',
  `tool_call_id` VARCHAR(120) NULL COMMENT '工具响应对应 ID',
  `tool_calls` JSON NULL COMMENT 'assistant 工具调用声明；参数脱敏',
  `attachment_refs` JSON NULL COMMENT '当前用户媒体逻辑 ID 数组；不保存 base64 内容',
  `citations` JSON NULL COMMENT '可验证的文档、段落和时间点引用',
  `status` ENUM('PENDING','COMPLETE','FAILED','CANCELLED') NOT NULL DEFAULT 'PENDING' COMMENT '消息状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_chat_message_1` (`id`, `user_id`),
  KEY `idx_chat_message_2` (`user_id`),
  UNIQUE KEY `uq_chat_message_3` (`conversation_id`, `message_no`),
  CONSTRAINT `fk_chat_message_0` FOREIGN KEY (`conversation_id`, `user_id`) REFERENCES `chat_conversation` (`id`, `user_id`),
  CONSTRAINT `fk_chat_message_1` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_chat_message_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='聊天消息与工具消息';

-- P3 可恢复的文件解析任务与结果
CREATE TABLE `processing_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `job_uuid` CHAR(36) NOT NULL COMMENT '对外任务 UUID',
  `asset_id` BIGINT UNSIGNED NULL COMMENT '输入原件；文本任务可为空',
  `run_id` BIGINT UNSIGNED NULL COMMENT '完整 AI 任务；纯本地任务可为空',
  `job_type` ENUM('FOOD_IMAGE','PLAN_IMPORT','DOCUMENT_PARSE','VIDEO_GUIDE','REINDEX') NOT NULL COMMENT '处理种类',
  `idempotency_key` VARCHAR(100) NOT NULL COMMENT '用户范围内请求去重键',
  `status` ENUM('QUEUED','RUNNING','RETRY_WAIT','WAITING_CREDENTIAL','WAITING_INPUT','SUCCEEDED','FAILED','CANCELLED') NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态',
  `stage` VARCHAR(64) NOT NULL COMMENT '当前阶段，如 TRANSCRIBE / FRAME_ANALYSIS / INDEX',
  `progress_percent` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '进度提示；非模型准确率',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '工作任务尝试次数；不等于模型调用次数',
  `lease_owner` VARCHAR(100) NULL COMMENT '领取任务的 worker 标识',
  `lease_expires_at` DATETIME(3) NULL COMMENT '租约到期时间；防重复 worker',
  `retry_at` DATETIME(3) NULL COMMENT '下一次可尝试时间',
  `checkpoint_payload` JSON NULL COMMENT '已持久化阶段结果及引用；不含 Key 和媒体二进制',
  `result_schema_version` VARCHAR(32) NULL COMMENT '最终结果契约版本',
  `result_payload` JSON NULL COMMENT '长期保留的结构化分析结果；无原件、音轨、帧图片',
  `result_digest` BINARY(32) NULL COMMENT '结构化结果摘要，辅助去重和完整性检查',
  `started_at` DATETIME(3) NULL COMMENT 'UTC 开始时间',
  `finished_at` DATETIME(3) NULL COMMENT 'UTC 结束时间',
  `error_code` VARCHAR(64) NULL COMMENT '脱敏错误码',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_processing_job_1` (`id`, `user_id`),
  KEY `idx_processing_job_2` (`user_id`),
  UNIQUE KEY `uq_processing_job_3` (`job_uuid`),
  UNIQUE KEY `uq_processing_job_4` (`user_id`, `idempotency_key`),
  KEY `idx_processing_job_5` (`status`, `retry_at`, `lease_expires_at`),
  CONSTRAINT `ck_processing_job_0` CHECK (progress_percent <= 100),
  CONSTRAINT `fk_processing_job_0` FOREIGN KEY (`asset_id`, `user_id`) REFERENCES `media_asset` (`id`, `user_id`),
  CONSTRAINT `fk_processing_job_1` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_processing_job_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='可恢复的文件解析任务与结果';

-- P2 日报、周报与可重现指标快照
CREATE TABLE `analysis_report` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `report_uuid` CHAR(36) NOT NULL COMMENT '对外报告 ID',
  `report_kind` ENUM('DAILY','WEEKLY') NOT NULL COMMENT '报告种类',
  `period_start` DATE NOT NULL COMMENT '业务开始日期',
  `period_end` DATE NOT NULL COMMENT '业务结束日期',
  `input_data_revision` BIGINT UNSIGNED NOT NULL COMMENT '生成时用户数据修订号；不同于当前修订即过期',
  `revision_no` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '同一周期重算版本',
  `metrics_snapshot` JSON NOT NULL COMMENT '已记录摄入、缺失项、运动消耗、全天参考区间、完成度及公式版本',
  `narrative_text` TEXT NULL COMMENT 'AI 讲解；基础统计可不调用 AI',
  `citations` JSON NULL COMMENT '知识引用',
  `source_run_id` BIGINT UNSIGNED NULL COMMENT '可选付费 AI 任务',
  `status` ENUM('READY','FAILED') NOT NULL DEFAULT 'READY' COMMENT '报告状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_analysis_report_1` (`id`, `user_id`),
  KEY `idx_analysis_report_2` (`user_id`),
  UNIQUE KEY `uq_analysis_report_3` (`report_uuid`),
  UNIQUE KEY `uq_analysis_report_4` (`user_id`, `report_kind`, `period_start`, `period_end`, `revision_no`),
  KEY `idx_analysis_report_5` (`user_id`, `period_start`),
  CONSTRAINT `ck_analysis_report_0` CHECK (period_end >= period_start),
  CONSTRAINT `fk_analysis_report_0` FOREIGN KEY (`source_run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_analysis_report_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='日报、周报与可重现指标快照';

-- P2 每次付费模型调用与用量
CREATE TABLE `model_call` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `run_id` BIGINT UNSIGNED NOT NULL COMMENT '所属完整任务',
  `reservation_id` BIGINT UNSIGNED NOT NULL COMMENT '对应预算预占',
  `model_id` BIGINT UNSIGNED NOT NULL COMMENT '模型',
  `price_id` BIGINT UNSIGNED NOT NULL COMMENT '当时采用的原生币种价格版本',
  `credential_version` INT UNSIGNED NOT NULL COMMENT '当时的 Key 版本',
  `attempt_no` INT UNSIGNED NOT NULL COMMENT '任务内唯一调用尝试序号',
  `provider_request_id` VARCHAR(160) NULL COMMENT '供应商返回请求 ID',
  `status` ENUM('PREPARED','SENT','SUCCEEDED','FAILED_BEFORE_SEND','REJECTED','UNKNOWN') NOT NULL DEFAULT 'PREPARED' COMMENT '调用状态',
  `input_hit_tokens` BIGINT UNSIGNED NULL COMMENT '官方缓存命中输入 Token；NULL 未收到用量',
  `input_miss_tokens` BIGINT UNSIGNED NULL COMMENT '官方未命中输入 Token',
  `output_tokens` BIGINT UNSIGNED NULL COMMENT '官方 completion_tokens；包含按官方口径计费的输出',
  `reasoning_tokens` BIGINT UNSIGNED NULL COMMENT '输出中的推理 Token 子项；不重复计费',
  `usage_payload` JSON NULL COMMENT '供应商用量原始对象；不含 Key、原始图片或请求头',
  `currency` CHAR(3) NOT NULL COMMENT '价格币种',
  `calculated_cost` DECIMAL(20,10) NULL COMMENT '按用量和价格核算金额；未知时 NULL',
  `price_confidence` ENUM('KNOWN_TIER','CONSERVATIVE_TIER','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT '价格档位确定程度',
  `sent_at` DATETIME(3) NULL COMMENT 'UTC 发出时间，决定预算日',
  `finished_at` DATETIME(3) NULL COMMENT 'UTC 结束时间',
  `latency_ms` INT UNSIGNED NULL COMMENT '调用耗时',
  `error_code` VARCHAR(64) NULL COMMENT '脱敏错误码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_model_call_1` (`id`, `user_id`),
  KEY `idx_model_call_2` (`user_id`),
  UNIQUE KEY `uq_model_call_3` (`reservation_id`),
  UNIQUE KEY `uq_model_call_4` (`run_id`, `attempt_no`),
  KEY `idx_model_call_5` (`user_id`, `sent_at`),
  CONSTRAINT `ck_model_call_0` CHECK (calculated_cost IS NULL OR calculated_cost >= 0),
  CONSTRAINT `fk_model_call_0` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_model_call_1` FOREIGN KEY (`reservation_id`, `user_id`) REFERENCES `budget_reservation` (`id`, `user_id`),
  CONSTRAINT `fk_model_call_2` FOREIGN KEY (`model_id`) REFERENCES `model_catalog` (`id`),
  CONSTRAINT `fk_model_call_3` FOREIGN KEY (`price_id`) REFERENCES `model_price` (`id`),
  CONSTRAINT `fk_model_call_4` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='每次付费模型调用与用量';

-- P1 训练计划不可覆盖的版本
CREATE TABLE `training_plan` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_group_uuid` CHAR(36) NOT NULL COMMENT '同一计划各版本共享的逻辑 ID',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '该计划版本号',
  `base_plan_id` BIGINT UNSIGNED NULL COMMENT '修改基于的旧版本',
  `title` VARCHAR(160) NOT NULL COMMENT '计划名称',
  `source` ENUM('MANUAL','IMPORT','AI') NOT NULL COMMENT '来源',
  `source_job_id` BIGINT UNSIGNED NULL COMMENT '导入任务',
  `source_run_id` BIGINT UNSIGNED NULL COMMENT 'AI 生成任务',
  `status` ENUM('DRAFT','ACTIVE','ARCHIVED','DISCARDED') NOT NULL DEFAULT 'DRAFT' COMMENT '先保存草稿，用户确认后激活',
  `goal_snapshot` JSON NOT NULL COMMENT '生成时个人目标、器械与限制快照',
  `starts_on` DATE NOT NULL COMMENT '计划生效开始日期',
  `ends_on` DATE NULL COMMENT '可选结束日期',
  `confirmed_at` DATETIME(3) NULL COMMENT '用户确认时间',
  `active_user_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN user_id ELSE NULL END) STORED COMMENT '每用户最多一个 ACTIVE 计划',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_training_plan_1` (`id`, `user_id`),
  KEY `idx_training_plan_2` (`user_id`),
  UNIQUE KEY `uq_training_plan_3` (`user_id`, `plan_group_uuid`, `revision_no`),
  UNIQUE KEY `uq_training_plan_4` (`active_user_id`),
  CONSTRAINT `ck_training_plan_0` CHECK (revision_no > 0),
  CONSTRAINT `ck_training_plan_1` CHECK (ends_on IS NULL OR ends_on >= starts_on),
  CONSTRAINT `fk_training_plan_0` FOREIGN KEY (`base_plan_id`, `user_id`) REFERENCES `training_plan` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_1` FOREIGN KEY (`source_job_id`, `user_id`) REFERENCES `processing_job` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_2` FOREIGN KEY (`source_run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_3` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='训练计划不可覆盖的版本';

-- P1 饮食记录头
CREATE TABLE `meal_record` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `eaten_at` DATETIME(3) NOT NULL COMMENT 'UTC 进餐时间',
  `business_date` DATE NOT NULL COMMENT '按记录时用户时区确定的业务日期',
  `meal_type` ENUM('BREAKFAST','LUNCH','DINNER','SNACK','OTHER') NOT NULL COMMENT '餐次',
  `source_asset_id` BIGINT UNSIGNED NULL COMMENT '手机原始图片的逻辑元数据',
  `source_job_id` BIGINT UNSIGNED NULL COMMENT '可选图片识别任务',
  `status` ENUM('DRAFT','CONFIRMED') NOT NULL DEFAULT 'DRAFT' COMMENT '用户确认后计入正式统计',
  `client_record_uuid` CHAR(36) NOT NULL COMMENT '客户端去重 UUID',
  `notes` VARCHAR(1000) NULL COMMENT '备注',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_meal_record_1` (`id`, `user_id`),
  KEY `idx_meal_record_2` (`user_id`),
  UNIQUE KEY `uq_meal_record_3` (`user_id`, `client_record_uuid`),
  KEY `idx_meal_record_4` (`user_id`, `business_date`, `status`),
  CONSTRAINT `fk_meal_record_0` FOREIGN KEY (`source_asset_id`, `user_id`) REFERENCES `media_asset` (`id`, `user_id`),
  CONSTRAINT `fk_meal_record_1` FOREIGN KEY (`source_job_id`, `user_id`) REFERENCES `processing_job` (`id`, `user_id`),
  CONSTRAINT `fk_meal_record_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='饮食记录头';

-- P3 系统知识或用户私有解析资料
CREATE TABLE `knowledge_document` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `owner_user_id` BIGINT UNSIGNED NULL COMMENT 'PRIVATE 为所属用户；SYSTEM 为 NULL',
  `scope` ENUM('SYSTEM','PRIVATE') NOT NULL COMMENT '资料可见范围',
  `document_uuid` CHAR(36) NOT NULL COMMENT '逻辑文档 ID',
  `title` VARCHAR(200) NOT NULL COMMENT '文档或视频标题',
  `document_kind` ENUM('TEXT','PDF','IMAGE_TEXT','VIDEO') NOT NULL COMMENT '资料类型',
  `source_asset_id` BIGINT UNSIGNED NULL COMMENT '私有资料手机原件元数据',
  `source_job_id` BIGINT UNSIGNED NULL COMMENT '私有解析任务',
  `source_url` VARCHAR(1000) NULL COMMENT '系统资料或用户主动提供的来源 URL；首版不自动抓取',
  `trust_level` ENUM('CURATED','USER_UPLOADED','UNVERIFIED') NOT NULL DEFAULT 'UNVERIFIED' COMMENT '来源等级；上传不会自动成为系统知识',
  `document_version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '内容变更时递增',
  `content_text` LONGTEXT NULL COMMENT '长期保留的解析文本；视频含转写及视觉观察描述',
  `status` ENUM('PARSING','READY','INDEXING','INDEXED','DELETED','FAILED') NOT NULL DEFAULT 'PARSING' COMMENT '文档与索引状态',
  `embedding_model_code` VARCHAR(120) NULL COMMENT '该版本使用的本地向量模型',
  `embedding_model_revision` VARCHAR(120) NULL COMMENT '实际模型 revision 或权重摘要',
  `vector_dimension` SMALLINT UNSIGNED NULL COMMENT '实际输出维度；不跨模型混用',
  `deleted_at` DATETIME(3) NULL COMMENT '删除时立即阻止检索',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_knowledge_document_1` (`document_uuid`),
  KEY `idx_knowledge_document_2` (`owner_user_id`, `scope`, `status`),
  CONSTRAINT `ck_knowledge_document_0` CHECK ((scope = 'SYSTEM' AND owner_user_id IS NULL AND source_asset_id IS NULL AND source_job_id IS NULL) OR (scope = 'PRIVATE' AND owner_user_id IS NOT NULL)),
  CONSTRAINT `fk_knowledge_document_0` FOREIGN KEY (`owner_user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `fk_knowledge_document_1` FOREIGN KEY (`source_asset_id`, `owner_user_id`) REFERENCES `media_asset` (`id`, `user_id`),
  CONSTRAINT `fk_knowledge_document_2` FOREIGN KEY (`source_job_id`, `owner_user_id`) REFERENCES `processing_job` (`id`, `user_id`)
) ENGINE=InnoDB COMMENT='系统知识或用户私有解析资料';

-- P2 工具执行及运行事件轨迹
CREATE TABLE `agent_step` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `run_id` BIGINT UNSIGNED NOT NULL COMMENT '所属任务',
  `model_call_id` BIGINT UNSIGNED NULL COMMENT '触发该事件的模型调用',
  `step_no` INT UNSIGNED NOT NULL COMMENT '任务内事件序号',
  `event_type` ENUM('TOOL','VALIDATION','CONTEXT','FINALIZATION','ERROR') NOT NULL COMMENT '运行事件类型',
  `tool_name` VARCHAR(100) NULL COMMENT '已注册工具名',
  `tool_call_id` VARCHAR(120) NULL COMMENT '模型工具调用 ID',
  `arguments_redacted` JSON NULL COMMENT '通过校验后的脱敏参数',
  `result_summary` JSON NULL COMMENT '限长结果摘要与证据引用',
  `status` ENUM('STARTED','SUCCEEDED','FAILED','SKIPPED') NOT NULL COMMENT '事件状态',
  `duration_ms` INT UNSIGNED NULL COMMENT '耗时',
  `error_code` VARCHAR(64) NULL COMMENT '错误码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_agent_step_1` (`id`, `user_id`),
  KEY `idx_agent_step_2` (`user_id`),
  UNIQUE KEY `uq_agent_step_3` (`run_id`, `step_no`),
  CONSTRAINT `fk_agent_step_0` FOREIGN KEY (`run_id`, `user_id`) REFERENCES `agent_run` (`id`, `user_id`),
  CONSTRAINT `fk_agent_step_1` FOREIGN KEY (`model_call_id`, `user_id`) REFERENCES `model_call` (`id`, `user_id`),
  CONSTRAINT `fk_agent_step_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='工具执行及运行事件轨迹';

-- P1 每周计划日模板
CREATE TABLE `training_plan_day` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT '明确计划版本',
  `iso_weekday` TINYINT UNSIGNED NOT NULL COMMENT 'ISO 星期：1 周一到 7 周日',
  `title` VARCHAR(120) NULL COMMENT '当日主题',
  `is_rest_day` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户计划中的休息日',
  `notes` VARCHAR(800) NULL COMMENT '说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_training_plan_day_1` (`id`, `user_id`),
  KEY `idx_training_plan_day_2` (`user_id`),
  UNIQUE KEY `uq_training_plan_day_3` (`plan_id`, `iso_weekday`),
  CONSTRAINT `ck_training_plan_day_0` CHECK (iso_weekday >= 1 AND iso_weekday <= 7),
  CONSTRAINT `fk_training_plan_day_0` FOREIGN KEY (`plan_id`, `user_id`) REFERENCES `training_plan` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_day_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='每周计划日模板';

-- P1 确认后的食物及营养快照
CREATE TABLE `meal_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `meal_id` BIGINT UNSIGNED NOT NULL COMMENT '饮食记录',
  `position_no` SMALLINT UNSIGNED NOT NULL COMMENT '条目顺序',
  `food_reference_id` BIGINT UNSIGNED NULL COMMENT '匹配的有来源食物条目',
  `display_name` VARCHAR(160) NOT NULL COMMENT '用户确认的食物名',
  `portion_description` VARCHAR(300) NULL COMMENT '如小碗、半份；不能代替可靠克重',
  `grams` DECIMAL(9,3) NULL COMMENT '估算或实测可食部克重；不明为空',
  `quantity_source` ENUM('WEIGHED','USER_ESTIMATE','IMAGE_ASSUMPTION','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT '分量依据',
  `nutrition_source` ENUM('REFERENCE','LABEL','USER_INPUT','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT '营养值依据',
  `energy_kcal_per_100g` DECIMAL(9,3) NULL COMMENT '确认时热量密度快照',
  `protein_g_per_100g` DECIMAL(7,3) NULL COMMENT '蛋白质密度快照',
  `fat_g_per_100g` DECIMAL(7,3) NULL COMMENT '脂肪密度快照',
  `carb_g_per_100g` DECIMAL(7,3) NULL COMMENT '碳水密度快照',
  `energy_kcal` DECIMAL(10,3) NULL COMMENT '代码计算热量；缺输入为空',
  `protein_g` DECIMAL(9,3) NULL COMMENT '代码计算蛋白质',
  `fat_g` DECIMAL(9,3) NULL COMMENT '代码计算脂肪',
  `carb_g` DECIMAL(9,3) NULL COMMENT '代码计算碳水',
  `source_snapshot` JSON NULL COMMENT '来源、版本、做法、分量假设及用户纠正；不把模型自报置信度当概率',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_meal_item_1` (`id`, `user_id`),
  KEY `idx_meal_item_2` (`user_id`),
  UNIQUE KEY `uq_meal_item_3` (`meal_id`, `position_no`),
  CONSTRAINT `ck_meal_item_0` CHECK (grams IS NULL OR grams > 0),
  CONSTRAINT `ck_meal_item_1` CHECK (energy_kcal_per_100g IS NULL OR energy_kcal_per_100g >= 0),
  CONSTRAINT `ck_meal_item_2` CHECK (energy_kcal IS NULL OR energy_kcal >= 0),
  CONSTRAINT `ck_meal_item_3` CHECK (protein_g IS NULL OR protein_g >= 0),
  CONSTRAINT `ck_meal_item_4` CHECK (fat_g IS NULL OR fat_g >= 0),
  CONSTRAINT `ck_meal_item_5` CHECK (carb_g IS NULL OR carb_g >= 0),
  CONSTRAINT `fk_meal_item_0` FOREIGN KEY (`meal_id`, `user_id`) REFERENCES `meal_record` (`id`, `user_id`),
  CONSTRAINT `fk_meal_item_1` FOREIGN KEY (`food_reference_id`) REFERENCES `food_reference` (`id`),
  CONSTRAINT `fk_meal_item_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='确认后的食物及营养快照';

-- P3 可引用的知识片段
CREATE TABLE `knowledge_chunk` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `document_id` BIGINT UNSIGNED NOT NULL COMMENT '所属资料；权限始终回查父文档',
  `document_version` INT UNSIGNED NOT NULL COMMENT '对应资料版本',
  `chunk_no` INT UNSIGNED NOT NULL COMMENT '片段序号',
  `content_text` TEXT NOT NULL COMMENT '分段后的原始文字或视觉观察摘要',
  `content_hash` BINARY(32) NOT NULL COMMENT '文本摘要',
  `page_start` INT UNSIGNED NULL COMMENT '文档起始页；视频为空',
  `page_end` INT UNSIGNED NULL COMMENT '文档结束页',
  `start_ms` BIGINT UNSIGNED NULL COMMENT '视频片段开始毫秒',
  `end_ms` BIGINT UNSIGNED NULL COMMENT '视频片段结束毫秒',
  `evidence_kind` ENUM('SOURCE_TEXT','ASR_TRANSCRIPT','VISUAL_OBSERVATION','COMBINED') NOT NULL COMMENT '证据类型；不混淆转写与画面观察',
  `vector_point_uuid` CHAR(36) NULL COMMENT 'Qdrant point UUID',
  `index_status` ENUM('PENDING','INDEXED','DELETE_PENDING','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT '向量状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_knowledge_chunk_1` (`document_id`, `document_version`, `chunk_no`),
  UNIQUE KEY `uq_knowledge_chunk_2` (`vector_point_uuid`),
  CONSTRAINT `ck_knowledge_chunk_0` CHECK (page_start IS NULL OR page_start > 0),
  CONSTRAINT `ck_knowledge_chunk_1` CHECK (page_end IS NULL OR page_end >= page_start),
  CONSTRAINT `ck_knowledge_chunk_2` CHECK (start_ms IS NULL OR (end_ms IS NOT NULL AND end_ms > start_ms)),
  CONSTRAINT `fk_knowledge_chunk_0` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`)
) ENGINE=InnoDB COMMENT='可引用的知识片段';

-- P1 计划日内训练动作
CREATE TABLE `training_plan_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_day_id` BIGINT UNSIGNED NOT NULL COMMENT '计划日',
  `exercise_id` BIGINT UNSIGNED NOT NULL COMMENT '动作字典',
  `position_no` SMALLINT UNSIGNED NOT NULL COMMENT '动作顺序',
  `target_sets` SMALLINT UNSIGNED NULL COMMENT '力量训练目标组数',
  `reps_min` SMALLINT UNSIGNED NULL COMMENT '目标次数下界',
  `reps_max` SMALLINT UNSIGNED NULL COMMENT '目标次数上界',
  `target_external_load_kg` DECIMAL(8,2) NULL COMMENT '目标外部负重；不代表自身体重',
  `target_duration_seconds` INT UNSIGNED NULL COMMENT '时长型训练目标秒数',
  `rest_seconds` SMALLINT UNSIGNED NULL COMMENT '计划组间休息秒数',
  `notes` VARCHAR(800) NULL COMMENT '动作说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_training_plan_item_1` (`id`, `user_id`),
  KEY `idx_training_plan_item_2` (`user_id`),
  UNIQUE KEY `uq_training_plan_item_3` (`plan_day_id`, `position_no`),
  CONSTRAINT `ck_training_plan_item_0` CHECK (target_sets IS NULL OR target_sets > 0),
  CONSTRAINT `ck_training_plan_item_1` CHECK (reps_min IS NULL OR reps_min > 0),
  CONSTRAINT `ck_training_plan_item_2` CHECK (reps_max IS NULL OR reps_max >= reps_min),
  CONSTRAINT `ck_training_plan_item_3` CHECK (target_external_load_kg IS NULL OR target_external_load_kg >= 0),
  CONSTRAINT `ck_training_plan_item_4` CHECK (target_duration_seconds IS NULL OR target_duration_seconds > 0),
  CONSTRAINT `fk_training_plan_item_0` FOREIGN KEY (`plan_day_id`, `user_id`) REFERENCES `training_plan_day` (`id`, `user_id`),
  CONSTRAINT `fk_training_plan_item_1` FOREIGN KEY (`exercise_id`) REFERENCES `exercise_reference` (`id`),
  CONSTRAINT `fk_training_plan_item_2` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='计划日内训练动作';

-- P1 实际训练场次
CREATE TABLE `workout_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `plan_day_id` BIGINT UNSIGNED NULL COMMENT '可选对应计划日版本；历史不随新计划改写',
  `business_date` DATE NOT NULL COMMENT '按记录时用户时区确定的日期',
  `started_at` DATETIME(3) NULL COMMENT 'UTC 开始时间',
  `ended_at` DATETIME(3) NULL COMMENT 'UTC 结束时间',
  `status` ENUM('DRAFT','COMPLETED','PARTIAL') NOT NULL DEFAULT 'DRAFT' COMMENT '完成状态',
  `client_record_uuid` CHAR(36) NOT NULL COMMENT '客户端去重 UUID',
  `perceived_fatigue` VARCHAR(40) NULL COMMENT '用户自报疲劳描述',
  `notes` VARCHAR(1000) NULL COMMENT '备注',
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本；更新时比较并递增',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_session_1` (`id`, `user_id`),
  KEY `idx_workout_session_2` (`user_id`),
  UNIQUE KEY `uq_workout_session_3` (`user_id`, `client_record_uuid`),
  KEY `idx_workout_session_4` (`user_id`, `business_date`),
  CONSTRAINT `ck_workout_session_0` CHECK (started_at IS NULL OR ended_at IS NULL OR ended_at >= started_at),
  CONSTRAINT `fk_workout_session_0` FOREIGN KEY (`plan_day_id`, `user_id`) REFERENCES `training_plan_day` (`id`, `user_id`),
  CONSTRAINT `fk_workout_session_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='实际训练场次';

-- P1 实际动作及运动消耗输入
CREATE TABLE `workout_entry` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `session_id` BIGINT UNSIGNED NOT NULL COMMENT '训练场次',
  `exercise_id` BIGINT UNSIGNED NOT NULL COMMENT '实际动作',
  `plan_item_id` BIGINT UNSIGNED NULL COMMENT '可选对应计划动作',
  `position_no` SMALLINT UNSIGNED NOT NULL COMMENT '动作顺序',
  `duration_seconds` INT UNSIGNED NULL COMMENT '该活动记录覆盖的持续时间；缺失则不算消耗',
  `distance_m` DECIMAL(10,2) NULL COMMENT '可选距离，m',
  `met_activity_id` BIGINT UNSIGNED NULL COMMENT '与实际活动和强度匹配的来源 MET',
  `weight_snapshot_kg` DECIMAL(6,2) NULL COMMENT '计算时采用的体重',
  `gross_energy_kcal` DECIMAL(10,3) NULL COMMENT '该时段总能耗估算，含静息部分；缺数据为 NULL',
  `net_energy_kcal` DECIMAL(10,3) NULL COMMENT '按标准 1 MET 扣除静息的增量估算；不用于加到 PAL 总量',
  `calculation_snapshot` JSON NULL COMMENT '公式版本、MET来源、时长、体重、假设和缺失信息',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_entry_1` (`id`, `user_id`),
  KEY `idx_workout_entry_2` (`user_id`),
  UNIQUE KEY `uq_workout_entry_3` (`session_id`, `position_no`),
  CONSTRAINT `ck_workout_entry_0` CHECK (duration_seconds IS NULL OR duration_seconds > 0),
  CONSTRAINT `ck_workout_entry_1` CHECK (distance_m IS NULL OR distance_m >= 0),
  CONSTRAINT `ck_workout_entry_2` CHECK (weight_snapshot_kg IS NULL OR weight_snapshot_kg > 0),
  CONSTRAINT `ck_workout_entry_3` CHECK (gross_energy_kcal IS NULL OR gross_energy_kcal >= 0),
  CONSTRAINT `ck_workout_entry_4` CHECK (net_energy_kcal IS NULL OR net_energy_kcal >= 0),
  CONSTRAINT `fk_workout_entry_0` FOREIGN KEY (`session_id`, `user_id`) REFERENCES `workout_session` (`id`, `user_id`),
  CONSTRAINT `fk_workout_entry_1` FOREIGN KEY (`exercise_id`) REFERENCES `exercise_reference` (`id`),
  CONSTRAINT `fk_workout_entry_2` FOREIGN KEY (`plan_item_id`, `user_id`) REFERENCES `training_plan_item` (`id`, `user_id`),
  CONSTRAINT `fk_workout_entry_3` FOREIGN KEY (`met_activity_id`) REFERENCES `met_activity` (`id`),
  CONSTRAINT `fk_workout_entry_4` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='实际动作及运动消耗输入';

-- P1 力量动作的实际逐组记录
CREATE TABLE `workout_set` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键；API 统一以字符串返回',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户；从登录上下文取得',
  `entry_id` BIGINT UNSIGNED NOT NULL COMMENT '实际动作条目',
  `set_no` SMALLINT UNSIGNED NOT NULL COMMENT '组序号',
  `reps` SMALLINT UNSIGNED NULL COMMENT '实际完成次数；未记录为空',
  `external_load_kg` DECIMAL(8,2) NULL COMMENT '实际外部负重；自重训练缺失时不推算负重',
  `is_completed` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '用户确认该组完成',
  `notes` VARCHAR(500) NULL COMMENT '用户主观感受或组备注',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC 创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC 最后更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_workout_set_1` (`id`, `user_id`),
  KEY `idx_workout_set_2` (`user_id`),
  UNIQUE KEY `uq_workout_set_3` (`entry_id`, `set_no`),
  CONSTRAINT `ck_workout_set_0` CHECK (set_no > 0),
  CONSTRAINT `ck_workout_set_1` CHECK (reps IS NULL OR reps > 0),
  CONSTRAINT `ck_workout_set_2` CHECK (external_load_kg IS NULL OR external_load_kg >= 0),
  CONSTRAINT `fk_workout_set_0` FOREIGN KEY (`entry_id`, `user_id`) REFERENCES `workout_entry` (`id`, `user_id`),
  CONSTRAINT `fk_workout_set_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`)
) ENGINE=InnoDB COMMENT='力量动作的实际逐组记录';

-- 以下仅供应商与价格配置，不包含用户、真实凭据、虚构营养数据或临床阈值。
INSERT INTO model_provider (id, provider_code, display_name, base_url, balance_supported, adapter_code) VALUES (1,'deepseek','DeepSeek','https://api.deepseek.com',TRUE,'deepseek-chat-completions');
INSERT INTO model_catalog (id,provider_id,model_code,display_name,supports_vision,supports_tools,supports_json,capability_snapshot,source_checked_on) VALUES
(1,1,'deepseek-flash','DeepSeek Flash',TRUE,TRUE,TRUE,JSON_OBJECT('checked_on','2026-10-12','input','text,image','source','https://api-docs.deepseek.com/zh-cn/quick_start/pricing/'),'2026-10-12'),
(2,1,'deepseek-v4-pro','DeepSeek V4 Pro',FALSE,TRUE,TRUE,JSON_OBJECT('checked_on','2026-10-12','input','text','source','https://api-docs.deepseek.com/zh-cn/quick_start/pricing/'),'2026-10-12');
-- 官方 CNY 报价快照；必须核查新价格及节假日规则后启用真实预算。预占统一采用 PEAK 保守档。
INSERT INTO model_price (model_id,price_version,currency,rate_tier,input_hit_per_million,input_miss_per_million,output_per_million,schedule_policy,source_url,source_checked_on,enabled) VALUES
(1,'observed-2026-10-12','CNY','PEAK',0.04,2,8,JSON_OBJECT('timezone','Asia/Shanghai','peak_windows','09:00-12:00,14:00-18:00','days','Mon-Fri excluding Chinese public holidays','holiday_calendar_verified',FALSE,'reservation_tier','PEAK'),'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/','2026-10-12',TRUE),
(1,'observed-2026-10-12','CNY','OFF_PEAK',0.02,1,4,JSON_OBJECT('timezone','Asia/Shanghai','peak_windows','09:00-12:00,14:00-18:00','days','Mon-Fri excluding Chinese public holidays','holiday_calendar_verified',FALSE,'reservation_tier','PEAK'),'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/','2026-10-12',TRUE),
(2,'observed-2026-10-12','CNY','PEAK',0.30,9,27,JSON_OBJECT('timezone','Asia/Shanghai','peak_windows','09:00-12:00,14:00-18:00','days','Mon-Fri excluding Chinese public holidays','holiday_calendar_verified',FALSE,'reservation_tier','PEAK'),'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/','2026-10-12',TRUE),
(2,'observed-2026-10-12','CNY','OFF_PEAK',0.15,4.5,13.5,JSON_OBJECT('timezone','Asia/Shanghai','peak_windows','09:00-12:00,14:00-18:00','days','Mon-Fri excluding Chinese public holidays','holiday_calendar_verified',FALSE,'reservation_tier','PEAK'),'https://api-docs.deepseek.com/zh-cn/quick_start/pricing/','2026-10-12',TRUE);

-- 规则目录种子；仅元数据，表达式不允许 eval；计算由注册的 Java 纯函数执行。
INSERT INTO calculation_rule (rule_code,rule_version,display_name,rule_kind,expression_text,parameters,source_urls,source_checked_on) VALUES
('BMI','v1.0','身体质量指数','SOURCE_FORMULA','weight_kg / (height_cm / 100)^2','{"input_units":{"weight":"kg","height":"cm"},"output_unit":"kg/m2","classification":"none"}','["https://www.fao.org/4/y5686e/y5686e04.htm"]','2026-10-12'),
('REE_MIFFLIN','v1.0','静息消耗预测','SOURCE_FORMULA','male:10W+6.25H-5A+5; female:10W+6.25H-5A-161','{"W":"kg","H":"cm","A":"complete years","output_unit":"kcal/day","application_age_min":19,"application_age_max":59,"missing":"null"}','["https://pubmed.ncbi.nlm.nih.gov/2305711/"]','2026-10-12'),
('BMR_SCHOFIELD','v1.0','FAO来源基础代谢预测','SOURCE_FORMULA','19<=A<30 male:15.057W+692.2; female:14.818W+486.6; 30<=A<60 male:11.472W+873.1; female:8.126W+845.6','{"W":"kg","A":"complete years","output_unit":"kcal/day","application_age_min":19,"application_age_max":59,"age_boundary":"half-open implementation convention"}','["https://www.fao.org/4/y5686e/y5686e07.htm"]','2026-10-12'),
('TEE_PAL_RANGE','v1.0','长期全天能耗参考区间','SOURCE_FORMULA','[BMR*PAL_low, BMR*PAL_high]','{"bmr_rule":"BMR_SCHOFIELD","pal":{"LIGHT":[1.4,1.69],"MODERATE":[1.7,1.99],"VIGOROUS":[2,2.4]},"includes_habitual_exercise":true,"is_confidence_interval":false,"output_unit":"kcal/day"}','["https://www.fao.org/4/y5686e/y5686e04.htm","https://www.fao.org/4/y5686e/y5686e07.htm"]','2026-10-12'),
('MET_GROSS','v1.0','活动时段总能耗估算','SOURCE_FORMULA','MET*weight_kg*duration_seconds/3600','{"standard_met":"1 kcal/kg/hour","includes_resting":true,"population":"ADULT_19_59","output_unit":"kcal"}','["https://pacompendium.com/","https://pacompendium.com/adult-compendium/"]','2026-10-12'),
('MET_NET_STANDARD','v1.0','相对标准静息的活动增量','ENGINEERING_METRIC','max(MET-1,0)*weight_kg*duration_seconds/3600','{"standard_resting_met":1,"not_individually_measured_resting":true,"output_unit":"kcal","never_add_to_pal_total":true}','["https://pacompendium.com/"]','2026-10-12'),
('FOOD_PER_100G','v1.0','来源营养密度按克数换算','ENGINEERING_METRIC','grams*density_per_100g/100','{"density_source":"reference/label/user_input","missing":"null per nutrient","require_matching_preparation":true,"units":["kcal","g"]}','["https://fdc.nal.usda.gov/api-guide/"]','2026-10-12'),
('KJ_TO_KCAL','v1.0','营养标签能量换算','SOURCE_FORMULA','kJ/4.184','{"output_unit":"kcal"}','["https://www.fao.org/4/y5686e/y5686e04.htm"]','2026-10-12'),
('PLAN_SETS_COMPLETION','v1.0','计划组数完成度','ENGINEERING_METRIC','sum(min(actual_completed_sets_j,planned_sets_j))/sum(planned_sets_j)*100','{"extra_sets":"separate","no_plan":"null","not_physiological_effectiveness":true,"unit":"percent"}','[]','2026-10-12'),
('PLAN_TIME_COMPLETION','v1.0','计划时长完成度','ENGINEERING_METRIC','sum(min(actual_seconds_j,target_seconds_j))/sum(target_seconds_j)*100','{"missing_actual":"mark incomplete","no_target":"null","unit":"percent"}','[]','2026-10-12'),
('EXTERNAL_LOAD_VOLUME','v1.0','完成组外部负重训练量','ENGINEERING_METRIC','sum(external_load_kg*reps)','{"compare":"same exercise and recording convention","bodyweight":"do not substitute","unknown":"exclude and flag","unit":"kg"}','[]','2026-10-12'),
('MODEL_TOKEN_COST','v1.0','调用用量按来源价格核算','ENGINEERING_METRIC','(hit_tokens*hit_price+miss_tokens*miss_price+completion_tokens*output_price)/1000000','{"price_unit":"native currency per million tokens","reasoning_tokens":"not added again","unknown_usage":"null and held reservation","account_balance":"not per-request bill"}','["https://api-docs.deepseek.com/zh-cn/quick_start/pricing/","https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/"]','2026-10-12');
