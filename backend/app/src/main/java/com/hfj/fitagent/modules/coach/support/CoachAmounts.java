package com.hfj.fitagent.modules.coach.support;

import com.hfj.fitagent.modules.billing.CostCalculator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 金额 → 对外文本的唯一口径。
 *
 * <p>§11.1 第 3 条：所有金额以<b>字符串</b>传输，前端不做算术只格式化。
 * scale 固定取 {@link CostCalculator#MONEY_SCALE}（10），与 {@code DECIMAL(20,10)} 列宽一致——
 * 用 2 位就抹掉了缓存命中价下 1000 token 的量级（0.0000000002 元），
 * 而 §12 要求"低于显示精度写『低于 0.0001 元』"，那需要前端拿到真实值才能判断。
 *
 * <p>{@code null} 原样返回 {@code null}：它表示"当前无法核算"，
 * 绝不落成 "0.0000000000"（那会把一次用量不明的调用显示成免费）。
 */
public final class CoachAmounts {

    private CoachAmounts() {
    }

    public static String plain(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(CostCalculator.MONEY_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** 同币种相加（跨币种在此直接拒绝：§6.3"不同币种永不相减"的同一立场）。 */
    public static BigDecimal addSameCurrency(BigDecimal left, BigDecimal right, String currency,
                                             String expectedCurrency) {
        if (expectedCurrency != null && !expectedCurrency.equalsIgnoreCase(sanitize(currency))) {
            throw new IllegalArgumentException("金额跨币种不可求和: " + currency + " vs " + expectedCurrency);
        }
        BigDecimal a = left == null ? BigDecimal.ZERO : left;
        BigDecimal b = right == null ? BigDecimal.ZERO : right;
        return a.add(b).setScale(CostCalculator.MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
