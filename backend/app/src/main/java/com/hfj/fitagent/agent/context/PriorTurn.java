package com.hfj.fitagent.agent.context;

/**
 * 历史轮次：只有"user 原文 + 该轮最终可见的 assistant 文本"（docs/agent-v1-design.md §8）。
 *
 * <p>历史里的原始 tool 消息<b>不</b>进入新请求：那样会把上一轮的工具结果当成本轮的事实来源，
 * 而数据可能已经变了。需要个人数据就必须重新过真实工具。
 *
 * <p>两者都是<b>非权威文本</b>：不能改变权限、预算、工具能力，也不能替代工具读取。
 * 这个定性由 {@link CoachPromptTemplates#historyBlock(java.util.List)} 的包裹说明与系统模板共同执行。
 */
public record PriorTurn(String userText, String assistantText) {

    public PriorTurn {
        userText = userText == null ? "" : userText.trim();
        assistantText = assistantText == null ? "" : assistantText.trim();
    }

    public boolean isEmpty() {
        return userText.isEmpty() && assistantText.isEmpty();
    }
}
