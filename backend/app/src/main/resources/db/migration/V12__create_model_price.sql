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
