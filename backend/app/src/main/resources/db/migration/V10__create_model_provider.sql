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
