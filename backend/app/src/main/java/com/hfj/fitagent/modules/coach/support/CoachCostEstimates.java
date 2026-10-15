package com.hfj.fitagent.modules.coach.support;

import com.hfj.fitagent.modules.billing.CostCalculator;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.CostResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 发送前的费用预估与预算快照（docs/agent-v1-design.md §11 约束："estimate 的假设与每轮真实预占一致"）。
 *
 * <p>算法与 {@code BudgetGate} 的保守预占同口径：输入按<b>全未命中</b>缓存计价、输出按 maxOutput 满额、
 * 档位一律 PEAK。预估是"最多可能花这么多"的上界，不是供应商硬上限，也绝不因为给了预估就跳过执行时的闸门。
 */
public final class CoachCostEstimates {

    /** 问题文本之外固定要发出去的部分：系统规则 + 工具声明 + 一轮最小往返（保守取 1600 字符）。 */
    private static final int BASE_REQUEST_CHARS = 1600;
    private static final int CHARS_PER_TOKEN = 3;

    private CoachCostEstimates() {
    }

    public static long estimateInputTokens(String question) {
        int chars = BASE_REQUEST_CHARS + (question == null ? 0 : question.length());
        return Math.max(1, (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }

    /** 一次调用的保守费用；报价缺失时为 null（不假装 0 元，§6.2）。 */
    public static BigDecimal perCall(CostCalculator calculator, BudgetContext budget,
                                     long inputTokens, int maxOutputTokens) {
        CostResult result = calculator.estimate(inputTokens, maxOutputTokens, budget.peakPrices());
        return result.isKnown() ? result.amount() : null;
    }

    public static BigDecimal total(BigDecimal perCall, int modelCalls) {
        if (perCall == null) {
            return null;
        }
        return perCall.multiply(BigDecimal.valueOf(modelCalls)).setScale(CostCalculator.MONEY_SCALE,
                RoundingMode.HALF_UP);
    }

    /** 给页面的假设清单：把这些字显示出来，用户才知道这个数字是怎么来的。 */
    public static List<String> assumptions(int maxModelCalls, int maxToolCalls) {
        return List.of(
                "一次提问可能触发多次模型调用，上限 " + maxModelCalls + " 次，工具执行上限 " + maxToolCalls + " 次",
                "输入按未命中缓存价、输出按 maxOutputTokens 满额预占（保守上界，不是供应商硬上限）",
                "单价按 PEAK 档计：节假日日历没有机器可读来源，不假定当前处于谷段（协议 §7）",
                "本预估不发起任何收费请求，真实调用仍逐次经过预算闸门");
    }

    /** {@code agent_run.budget_snapshot}：发送前展示与事后追溯用，不是放行依据。 */
    public static Map<String, Object> budgetSnapshot(BudgetContext budget, long providerId, long modelId,
                                                     long estimatedInputTokens, long dataRevision) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("providerId", providerId);
        snapshot.put("modelId", modelId);
        snapshot.put("currency", budget.currency());
        snapshot.put("maxTaskCost", CoachAmounts.plain(budget.maxTaskCost()));
        snapshot.put("maxDailyCost", CoachAmounts.plain(budget.maxDailyCost()));
        snapshot.put("maxModelCalls", budget.maxModelCalls());
        snapshot.put("estimatedInputTokens", estimatedInputTokens);
        snapshot.put("dataRevision", dataRevision);
        if (budget.peakPrices() != null) {
            snapshot.put("priceVersion", budget.peakPrices().priceVersion());
            snapshot.put("rateTier", budget.peakPrices().rateTier());
        }
        // 用 unmodifiableMap 而不是 Map.copyOf：不设限的档位就是 null，写进 JSON 是"没有上限"的如实表达，
        // 而 copyOf 会因为 null 值直接抛 NPE
        return java.util.Collections.unmodifiableMap(snapshot);
    }
}
