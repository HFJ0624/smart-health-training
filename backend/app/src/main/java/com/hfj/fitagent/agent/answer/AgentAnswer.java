package com.hfj.fitagent.agent.answer;

import com.hfj.fitagent.agent.core.StopReason;

import java.util.List;

/**
 * 一次 run 的最终交付物（docs/agent-v1-design.md §3、§9）。
 *
 * <p>它会被完整写进 {@code agent_run.result_payload}，页面重进时读同一份持久化结果：
 * 事实快照不因费用状态更新而被静默重写。
 *
 * @param runStatus        agent_run.status 的终态字符串（与 stopReason 一致，由 StopReason 推导）
 * @param isStale          run 结束时的 dataRevision 与开始时不同 → 事实保留原快照但标记陈旧，不自动付费重跑
 * @param costKnown        本次 run 的所有调用都拿到了用量并已可结算
 * @param costUncertain    存在"已发送但结果未知"的调用 → 界面必须显示"用量不明"，不显示 0
 * @param contextTruncated 上下文是否按轮次边界丢弃过更早的内容（工程事实，不是健康结论）
 */
public record AgentAnswer(String answerText,
                          List<FactCard> factCards,
                          List<String> evidenceRefs,
                          List<String> missingFields,
                          Long dataRevision,
                          boolean isStale,
                          String runStatus,
                          StopReason stopReason,
                          UsageSummary usageSummary,
                          boolean costKnown,
                          boolean costUncertain,
                          boolean contextTruncated) {

    public AgentAnswer {
        answerText = answerText == null ? "" : answerText;
        factCards = factCards == null ? List.of() : List.copyOf(factCards);
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        usageSummary = usageSummary == null ? UsageSummary.empty() : usageSummary;
        if (stopReason != null && runStatus != null && !runStatus.equals(stopReason.runStatus())) {
            throw new IllegalArgumentException("runStatus 必须由 stopReason 推导，不能各写各的："
                    + runStatus + " vs " + stopReason.runStatus());
        }
        if (costKnown && costUncertain) {
            throw new IllegalArgumentException("费用不能同时是「已知」与「不确定」");
        }
    }

    public static AgentAnswer of(List<FactCard> factCards, List<String> evidenceRefs, List<String> missingFields,
                                 Long dataRevision, StopReason stopReason, UsageSummary usage,
                                 boolean costKnown, boolean costUncertain, boolean isStale,
                                 boolean contextTruncated, String answerText) {
        return new AgentAnswer(answerText, factCards, evidenceRefs, missingFields, dataRevision, isStale,
                stopReason.runStatus(), stopReason, usage, costKnown, costUncertain, contextTruncated);
    }

    /** 有真实事实支撑（而不只是自由文本）才算"本轮取到了数据"。 */
    public boolean hasVerifiedFacts() {
        return !factCards.isEmpty();
    }
}
