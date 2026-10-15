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
