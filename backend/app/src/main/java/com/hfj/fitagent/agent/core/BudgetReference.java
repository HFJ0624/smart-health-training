package com.hfj.fitagent.agent.core;

import java.util.Locale;

/**
 * 预算<b>引用</b>：指向本次 run 应记账的策略与日账本行，不含任何额度判断逻辑。
 *
 * <p>Agent 侧不实现 BudgetGate（那是 modules/billing 的职责），但必须把"往哪张账上记"传到调用点，
 * 否则网关无从做预占。字段可空表示装配时未取到（此时 billing 必须拒绝发起调用，而不是默认放行）。
 *
 * @param policyId     budget_policy.id
 * @param dailyUsageId budget_daily_usage.id（按调用开始时的业务预算日归属）
 * @param currency     预算币种（与供应商余额币种分开显示，不相减、不猜汇率）
 */
public record BudgetReference(Long policyId, Long dailyUsageId, String currency) {

    private static final String UNSET_DETAIL = "预算引用不完整：billing 必须拒绝发起调用（B10 禁止默认放行的 BudgetGate）";

    public BudgetReference {
        currency = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
        if (currency != null && currency.isBlank()) {
            currency = null;
        }
    }

    /** 网关在发起 HTTP 前自检；不完整就停止而不是"当作没预算"。 */
    public boolean complete() {
        // dailyUsageId 不参与判定：当天的 budget_daily_usage 行要到第一次预占才被创建，
        // 提交问题时它本就不存在。要求它存在等于宣布"每个新 run 都不许花钱"。
        return policyId != null && policyId > 0 && currency != null && !currency.isBlank();
    }

    public String unsetDetail() {
        return UNSET_DETAIL;
    }

    public static BudgetReference unset() {
        return new BudgetReference(null, null, null);
    }
}
