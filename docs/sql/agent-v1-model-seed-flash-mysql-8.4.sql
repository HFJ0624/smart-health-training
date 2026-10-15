-- =============================================================================
-- FitAgent · 模型目录与报价种子（deepseek-flash）
--
-- 前置：已执行 docs/sql/fitagent-full-schema-mysql-8.4.sql，21 张表已存在。
-- 库名：smart_health_training
--
-- 为什么需要这份脚本：建表脚本故意不含种子（迁移里的 V23 没出）。在
-- model_provider / model_catalog / model_price 有生效行之前，后端会以
-- MODEL_NOT_CONFIGURED 拒绝发起任何模型调用，「我的 → 模型设置」与 AI 教练页
-- 都只能显示空态。这份脚本补上**唯一有官方文档依据的那一行模型**。
--
-- ⚠ 数据来源与边界（必读）
--   数值来自 docs/agent-v1-protocol.md §1 与 §7，人工核查于 **2026-10-13**，
--   官方 pricing 页面没有标注自己的生效日期，所以这只能算「核查当日快照」。
--   单位：元人民币 / 1,000,000 tokens。
--     deepseek-flash  PEAK     命中 0.04 / 未命中 2 / 输出 8
--     deepseek-flash  OFF_PEAK 命中 0.02 / 未命中 1 / 输出 4   （谷段=峰的 50%）
--
--   只插 `deepseek-flash` 一行 ACTIVE，**不插 deepseek-v4-pro**：官方两条公告
--   互相矛盾（news260910 说自 2026-09-14 起 Pro 请求实际由 Flash 服务并按 Flash 计价，
--   2026-09-10 changelog 说 Pro 计费不变），没有 Key 无法实测。这不是省事，是防止
--   「按错的模型计费」。而且 ProviderCatalogService 在“该供应商有多个 ACTIVE 模型”
--   时会要求显式指定 modelCode，而 coach 侧传的是默认选择，所以插两行 ACTIVE 会直接把
--   调用打死——要保持「恰好一行 ACTIVE」。
--
--   ⚠ 这份种子一旦写进真实计费路径就是钱。启用前请你自己对
--      https://api-docs.deepseek.com/zh-cn/quick_start/pricing 复核一遍数字。
--      本文件不含 UPDATE/DELETE：改价要用**新的 price_version 行**，不覆盖历史行（§6.2）。
--
-- 可重复执行：每条 INSERT 都带 NOT EXISTS 保护，第二次跑不会产生第二行。
-- =============================================================================

USE `smart_health_training`;
SET NAMES utf8mb4;
SET time_zone = '+00:00';

-- ---------- 1) 供应商：DeepSeek ----------
-- base_url 权威值在表里而不是配置或请求：任何接口都不接受客户端改写外呼地址（防 SSRF）。
-- adapter_code=DEEPSEEK 绑定协议实现，用户不能选。
INSERT INTO `model_provider`
       (`provider_code`, `display_name`, `base_url`, `balance_supported`, `adapter_code`, `status`)
SELECT 'DEEPSEEK', 'DeepSeek（用户自带 Key）', 'https://api.deepseek.com', TRUE, 'DEEPSEEK', 'ACTIVE'
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM `model_provider` WHERE `provider_code` = 'DEEPSEEK');

-- ---------- 2) 模型目录：deepseek-flash（唯一 ACTIVE 行） ----------
-- ⚠ 文档路径不带 /v1：base_url 只到域名，chat/completions 由配置里的
--   fitagent.agent.model.deepseek.chat-completions-path 拼（protocol §2）。
INSERT INTO `model_catalog`
       (`provider_id`, `model_code`, `display_name`, `supports_vision`, `supports_tools`, `supports_json`,
        `max_context_tokens`, `max_output_tokens`, `capability_snapshot`, `source_checked_on`, `status`)
SELECT p.`id`, 'deepseek-flash', 'DeepSeek-V4.1-Flash', TRUE, TRUE, TRUE,
       1048576, 393216,
       JSON_OBJECT(
         'model', 'deepseek-flash',
         'display_name', 'DeepSeek-V4.1-Flash',
         'context_window', 1048576,
         'max_output_tokens', 393216,
         'default_output_tokens_non_thinking', 8192,
         'supports_tools', TRUE,
         'supports_vision', TRUE,
         'supports_json_output', TRUE,
         'thinking_mode_default', 'enabled',
         'thinking_mode_used_by_this_project', 'disabled',
         'thinking_note', '带 tools 时必须完整回传 reasoning_content，本项目未实现回传，因此每个请求显式 disabled',
         'max_concurrency', 2500,
         'source', 'https://api-docs.deepseek.com/'),
       DATE '2026-10-13', 'ACTIVE'
  FROM `model_provider` p
 WHERE p.`provider_code` = 'DEEPSEEK'
   AND NOT EXISTS (SELECT 1 FROM `model_catalog` c WHERE c.`provider_id` = p.`id` AND c.`model_code` = 'deepseek-flash');

-- ---------- 3) 报价：PEAK（预占与结算都用这一档）+ OFF_PEAK（仅留档，不启用） ----------
-- 本项目预占一律按 PEAK 保守档（节假日日历没有机器可读来源，不能断定某时刻在谷段），
-- 所以 OFF_PEAK 行 enabled=0：数字留档，但不参与计算，避免"以为自己在按谷段省钱"。
INSERT INTO `model_price`
       (`model_id`, `price_version`, `currency`, `rate_tier`,
        `input_hit_per_million`, `input_miss_per_million`, `output_per_million`,
        `source_url`, `source_checked_on`, `enabled`)
SELECT c.`id`, 1, 'CNY', 'PEAK', 0.0400000000, 2.0000000000, 8.0000000000,
       'https://api-docs.deepseek.com/zh-cn/quick_start/pricing', DATE '2026-10-13', TRUE
  FROM `model_catalog` c
  JOIN `model_provider` p ON p.`id` = c.`provider_id`
 WHERE p.`provider_code` = 'DEEPSEEK' AND c.`model_code` = 'deepseek-flash'
   AND NOT EXISTS (SELECT 1 FROM `model_price` mp
                     WHERE mp.`model_id` = c.`id` AND mp.`price_version` = 1
                       AND mp.`currency` = 'CNY' AND mp.`rate_tier` = 'PEAK');

INSERT INTO `model_price`
       (`model_id`, `price_version`, `currency`, `rate_tier`,
        `input_hit_per_million`, `input_miss_per_million`, `output_per_million`,
        `source_url`, `source_checked_on`, `enabled`)
SELECT c.`id`, 1, 'CNY', 'OFF_PEAK', 0.0200000000, 1.0000000000, 4.0000000000,
       'https://api-docs.deepseek.com/zh-cn/quick_start/pricing', DATE '2026-10-13', FALSE
  FROM `model_catalog` c
  JOIN `model_provider` p ON p.`id` = c.`provider_id`
 WHERE p.`provider_code` = 'DEEPSEEK' AND c.`model_code` = 'deepseek-flash'
   AND NOT EXISTS (SELECT 1 FROM `model_price` mp
                     WHERE mp.`model_id` = c.`id` AND mp.`price_version` = 1
                       AND mp.`currency` = 'CNY' AND mp.`rate_tier` = 'OFF_PEAK');

-- ---------- 4) 自检：三张表是否形成"可用的一条链" ----------
-- 期望：provider ACTIVE 1 行、catalog ACTIVE 恰好 1 行、price PEAK enabled 1 行。
-- catalog 的 ACTIVE 行数不等于 1 时，coach 的默认选模型会报 MODEL_NOT_CONFIGURED。
SELECT (SELECT COUNT(*) FROM `model_provider` WHERE `provider_code` = 'DEEPSEEK' AND `status` = 'ACTIVE')
         AS active_providers,
       (SELECT COUNT(*) FROM `model_catalog` c
          JOIN `model_provider` p ON p.`id` = c.`provider_id`
         WHERE p.`provider_code` = 'DEEPSEEK' AND c.`status` = 'ACTIVE')
         AS active_models,
       (SELECT COUNT(*) FROM `model_price` mp
          JOIN `model_catalog` c ON c.`id` = mp.`model_id`
          JOIN `model_provider` p ON p.`id` = c.`provider_id`
         WHERE p.`provider_code` = 'DEEPSEEK' AND mp.`enabled` = 1 AND mp.`rate_tier` = 'PEAK')
         AS enabled_peak_prices;

-- ---------- 5) 这份种子之后还缺什么（不自动帮你补，因为那是你的额度决策） ----------
-- 每个用户还需要一行 budget_policy，否则 BudgetGate 会以「预算策略未启用」拒绝调用。
-- 正确做法是在小程序「我的 → 模型设置 → 本应用预算」里填，让这行由产品流程产生：
-- 单次任务上限 + 当日上限（留空=不设限是不行的，至少要有一个上限），币种 CNY。
-- 想直接用 SQL 建也一样，字段口径与页面一致：
--   INSERT INTO budget_policy (user_id, provider_id, currency, max_task_cost, max_daily_cost,
--                              budget_timezone, max_model_calls, max_tool_calls, max_output_tokens, enabled)
--   SELECT <你的 user_id>, id, 'CNY', 0.5000000000, 5.0000000000, 'Asia/Shanghai', 4, 8, 1024, TRUE
--     FROM model_provider WHERE provider_code = 'DEEPSEEK';
