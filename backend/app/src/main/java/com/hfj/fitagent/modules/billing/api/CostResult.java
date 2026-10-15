package com.hfj.fitagent.modules.billing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 核算结果：要么"知道金额"，要么"知道不知道"，没有第三种"当 0 处理"。
 *
 * <p>{@code amount} 在 {@link Outcome#UNKNOWN} 时<b>恒为 null</b>：0 会往下游流成
 * "这次调用免费"，账面于是凭空多出可用额度（设计 §6.2、V16 注释里的 uncertain 分账正是为它准备的）。
 *
 * <p>{@code known && amount == 0} 是合法情形（用量确实为 0），与"未知"必须能区分开。
 *
 * @param outcome  KNOWN / UNKNOWN
 * @param amount   已核算金额（DECIMAL(20,10) 口径，scale 10）；UNKNOWN 时为 null
 * @param currency 金额币种；与 {@code UnitPrices.currency} 一致，跨币种永不相减
 * @param missing  导致不可核算的字段名（可审计，不猜值）
 */
public record CostResult(Outcome outcome, BigDecimal amount, String currency, List<String> missing) {

    public enum Outcome {
        /** 三个分项与三个单价都齐备。 */
        KNOWN,
        /** 任一缺项或存在负数：不给出金额。 */
        UNKNOWN
    }

    public CostResult {
        missing = missing == null ? List.of() : List.copyOf(missing);
        if (outcome == Outcome.KNOWN && amount == null) {
            throw new IllegalArgumentException("KNOWN 结果必须带金额");
        }
        if (outcome == Outcome.UNKNOWN && amount != null) {
            throw new IllegalArgumentException("UNKNOWN 结果的金额必须为 null（绝不补 0）");
        }
    }

    public static CostResult known(String currency, BigDecimal amount) {
        return new CostResult(Outcome.KNOWN, amount, currency, List.of());
    }

    public static CostResult unknown(String currency, List<String> missing) {
        return new CostResult(Outcome.UNKNOWN, null, currency, missing);
    }

    public boolean isKnown() {
        return outcome == Outcome.KNOWN;
    }

    public boolean isUnknown() {
        return outcome == Outcome.UNKNOWN;
    }
}
