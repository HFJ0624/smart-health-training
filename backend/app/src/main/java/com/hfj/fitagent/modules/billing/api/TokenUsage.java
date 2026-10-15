package com.hfj.fitagent.modules.billing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次模型调用的 token 用量（协议 §6 的字段口径）。
 *
 * <p>三个计价分项都用可空的 {@link Long}：官方语义是"缺字段一律 null，不填 0"（设计 §6.2、B10）。
 * 把"没给"落成 0 会把一次用量不明的调用算成"免费"，进而把预算账面做低。
 *
 * <p>{@code reasoningTokens} 是 {@code completion_tokens} 的<b>细分项</b>，
 * 存在的唯一理由是让"不重复计费"这件事在类型上可见：核算函数接收它但<b>绝不</b>加到输出分项上
 * （协议 §6 的算术证据：22 + 29 = 51，而 reasoning_tokens = 27 已在 29 内）。
 *
 * @param hitTokens    usage.prompt_cache_hit_tokens，null 表示未知
 * @param missTokens   usage.prompt_cache_miss_tokens，null 表示未知
 * @param outputTokens usage.completion_tokens，null 表示未知
 * @param reasoningTokens usage.completion_tokens_details.reasoning_tokens，<b>不参与</b>计价
 */
public record TokenUsage(Long hitTokens, Long missTokens, Long outputTokens, Long reasoningTokens) {

    /** 计价分项缺失即视为用量不明（reasoningTokens 缺不缺都不影响）。 */
    public boolean hasUnknownPricedField() {
        return hitTokens == null || missTokens == null || outputTokens == null;
    }

    /** 缺失字段的字段名，用于错误说明与 UNKNOWN 的可审计来源。 */
    public List<String> missingFields() {
        return java.util.stream.Stream.of(
                        hitTokens == null ? "promptCacheHitTokens" : null,
                        missTokens == null ? "promptCacheMissTokens" : null,
                        outputTokens == null ? "completionTokens" : null)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 负数不是"未知"也不是"零"，是数据错误；同样归入不可核算，避免给出一个偏小的费用。 */
    public boolean hasNegative() {
        return negative(hitTokens) || negative(missTokens) || negative(outputTokens) || negative(reasoningTokens);
    }

    private static boolean negative(Long v) {
        return v != null && v < 0;
    }
}
