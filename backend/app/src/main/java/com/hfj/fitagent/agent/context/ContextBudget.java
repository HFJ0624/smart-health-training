package com.hfj.fitagent.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 上下文预算（工程闸门，不是产品规则）。
 *
 * <p>为什么按"字符"而不是 token 计量：官方没有给出可用的 tokenizer 口径，
 * 而 {@code context_window = 1M}（docs/agent-v1-protocol.md §1）远大于我们允许的上下文。
 * 用一个保守的字符上限足以防止"把整段历史塞进请求"，同时不假装我们能算准 token。
 *
 * @param maxPriorTurns      最多带入的历史轮数（一问一答算一轮）
 * @param maxCharsPerTurn    单轮历史的字符上限（超出部分在该轮内截断）
 * @param maxContextChars    整条上下文（系统模板 + 日期块 + 历史 + 当前问题 + 本轮工作消息）的字符上限
 */
@ConfigurationProperties(prefix = "fitagent.agent.context")
public record ContextBudget(int maxPriorTurns, int maxCharsPerTurn, int maxContextChars) {

    public static final int DEFAULT_MAX_PRIOR_TURNS = 6;
    public static final int DEFAULT_MAX_CHARS_PER_TURN = 800;
    public static final int DEFAULT_MAX_CONTEXT_CHARS = 20000;

    public ContextBudget {
        maxPriorTurns = positive(maxPriorTurns, DEFAULT_MAX_PRIOR_TURNS);
        maxCharsPerTurn = positive(maxCharsPerTurn, DEFAULT_MAX_CHARS_PER_TURN);
        maxContextChars = positive(maxContextChars, DEFAULT_MAX_CONTEXT_CHARS);
        if (maxContextChars < maxCharsPerTurn * 2) {
            throw new IllegalStateException("fitagent.agent.context.max-context-chars 至少要容得下两轮历史");
        }
    }

    public static ContextBudget defaults() {
        return new ContextBudget(0, 0, 0);
    }

    /** 单轮历史裁剪：保留结尾部分（最新的话更有上下文价值），并明确标出被裁掉的长度。 */
    public String clampTurn(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() <= maxCharsPerTurn) {
            return trimmed;
        }
        return "…[历史已截断 " + (trimmed.length() - maxCharsPerTurn) + " 字符]…"
                + trimmed.substring(trimmed.length() - maxCharsPerTurn);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
