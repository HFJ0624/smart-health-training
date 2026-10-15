package com.hfj.fitagent.modules.billing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一百万 token 的三个单价（对应 {@code model_price} 的一行）。
 *
 * <p>字段可空是为了让"报价行缺某一项"如实变成"不可核算"，而不是被 0 补成一个便宜的价格。
 * 单价本身来自数据库，代码里没有任何数字常量（设计 §5 第 8 条）。
 *
 * @param currency          ISO-4217 大写；不同币种永不相减、不猜汇率
 * @param hitPerMillion     输入（缓存命中）单价
 * @param missPerMillion    输入（未命中）单价
 * @param outputPerMillion  输出单价
 * @param priceVersion      报价版本，写进账本便于事后追溯
 * @param rateTier          PEAK / OFF_PEAK
 */
public record UnitPrices(String currency,
                         BigDecimal hitPerMillion,
                         BigDecimal missPerMillion,
                         BigDecimal outputPerMillion,
                         Integer priceVersion,
                         String rateTier) {

    public List<String> missingFields() {
        return java.util.stream.Stream.of(
                        hitPerMillion == null ? "inputHitPerMillion" : null,
                        missPerMillion == null ? "inputMissPerMillion" : null,
                        outputPerMillion == null ? "outputPerMillion" : null)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public boolean hasNegative() {
        return negative(hitPerMillion) || negative(missPerMillion) || negative(outputPerMillion);
    }

    private static boolean negative(BigDecimal v) {
        return v != null && v.signum() < 0;
    }
}
