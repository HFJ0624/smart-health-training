package com.hfj.fitagent.agent.answer;

import com.hfj.fitagent.agent.model.ModelUsage;

import java.util.List;

/**
 * 用量小结（不是账单）。
 *
 * <p>口径纪律（docs/agent-v1-protocol.md §6）：
 * <ul>
 *   <li>输入分项缺失时 {@code promptTokens} 为 null，不按 0 计；</li>
 *   <li>{@code reasoningTokens} 是 completion 的<b>细分项</b>，只做累加展示，绝不与 completion 相加；</li>
 *   <li>只要有任何一次调用没有回报 usage，{@code usageKnown} 就是 false —— 界面要显示"存在用量不明"，
 *       而不是把已知部分当成全部。</li>
 * </ul>
 * 真正的费用以 billing 的结算记录为准，本记录只用于解释"这次跑了几个调用"。
 */
public record UsageSummary(int modelCalls,
                           int toolCalls,
                           Long promptTokens,
                           Long completionTokens,
                           Long reasoningTokens,
                           boolean usageKnown) {

    public static UsageSummary empty() {
        return new UsageSummary(0, 0, null, null, null, true);
    }

    public static UsageSummary of(List<ModelUsage> usages, int modelCalls, int toolCalls) {
        long prompt = 0L;
        long completion = 0L;
        long reasoning = 0L;
        boolean promptSeen = false;
        boolean completionSeen = false;
        boolean reasoningSeen = false;
        boolean allKnown = true;
        for (ModelUsage usage : usages) {
            if (usage == null || !usage.hasAnyKnown()) {
                allKnown = false;
                continue;
            }
            Long promptTotal = usage.promptTokens();
            if (promptTotal != null) {
                prompt += promptTotal;
                promptSeen = true;
            } else {
                allKnown = false;
            }
            if (usage.completionTokens() != null) {
                completion += usage.completionTokens();
                completionSeen = true;
            } else {
                allKnown = false;
            }
            // reasoning 缺失不代表未知，只代表这次调用没有思考细分（本轮思考模式关闭，常态就是 null）
            if (usage.reasoningTokens() != null) {
                reasoning += usage.reasoningTokens();
                reasoningSeen = true;
            }
        }
        return new UsageSummary(modelCalls, toolCalls,
                promptSeen ? prompt : null,
                completionSeen ? completion : null,
                reasoningSeen ? reasoning : null,
                allKnown);
    }

    public String describe() {
        return "模型调用 " + modelCalls + " 次 / 工具调用 " + toolCalls + " 次，"
                + (usageKnown && promptTokens != null && completionTokens != null
                ? "输入 " + promptTokens + " / 输出 " + completionTokens + " tokens"
                : "用量不完整（存在未回报的调用）");
    }
}
