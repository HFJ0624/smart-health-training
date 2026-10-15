package com.hfj.fitagent.modules.modelaccess.api;

import java.math.BigDecimal;

/**
 * 生效报价（V12 的对外形状）：调用方由此拿到单价，<b>不需要</b>接触凭据或实体。
 *
 * <p>三个单价都对应 {@code usage} 的一个分项，命中/未命中分开是官方真实机制（协议 §6），
 * 合成一个"输入单价"会把缓存命中的部分按未命中价算贵。
 *
 * <p>{@code rateTier} 显式带出档位：预占路径必须取 PEAK（协议 §7 未核实节假日日历），
 * 结算路径可取实际档，取不到实际档就标"保守核算"，不冒充供应商账单。
 *
 * @param currency       ISO-4217 大写；不同币种永不相减、不猜汇率
 * @param hit            输入（缓存命中）单价，元/1M tokens
 * @param miss           输入（未命中）单价，元/1M tokens
 * @param output         输出单价，元/1M tokens（reasoning_tokens 是其细分项，不另计价）
 * @param priceVersion   报价版本，写进账本便于追溯
 * @param rateTier       PEAK / OFF_PEAK
 * @param modelId        该报价所属模型行
 */
public record PriceQuote(String currency,
                         BigDecimal hit,
                         BigDecimal miss,
                         BigDecimal output,
                         int priceVersion,
                         String rateTier,
                         long modelId) {
}
