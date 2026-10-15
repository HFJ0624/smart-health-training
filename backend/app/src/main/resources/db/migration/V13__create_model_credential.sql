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
