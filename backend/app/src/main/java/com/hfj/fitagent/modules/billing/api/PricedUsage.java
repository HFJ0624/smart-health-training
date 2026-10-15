package com.hfj.fitagent.modules.billing.api;

import java.util.Objects;

/**
 * 结算入参：一次调用的真实用量 + 该次调用所依据的报价行。
 *
 * <p>{@code prices} 应当是<b>本次调用实际使用</b>的那一行（未来的 {@code model_call} 会存单价快照）：
 * 结算时重新查表会把中途调价算进已经发生的花费里。
 *
 * <p>币种一致性不在这里判：它必须对照台账行（{@code budget_reservation.currency}）来判，
 * 由 {@code UsageSettlement} 负责，跨币种即拒绝移动金额。
 */
public record PricedUsage(TokenUsage usage, UnitPrices prices) {

    public PricedUsage {
        Objects.requireNonNull(usage, "用量对象不能为 null（分项用 null 表示未知）");
    }

    /** 报价行缺失：核算结果必然是 UNKNOWN。 */
    public boolean missingPrices() {
        return prices == null;
    }
}
