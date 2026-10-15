package com.hfj.fitagent.agent.model;

import tools.jackson.databind.JsonNode;

/**
 * 供应商返回的用量分项（docs/agent-v1-protocol.md §6）。
 *
 * <p>三条硬规则：
 * <ol>
 *   <li><b>缺字段一律 null，不填 0</b>：0 会被下游当成"确实用了 0 token"，把"未知"伪装成"免费"，
 *       这是 B10 明确禁止的。</li>
 *   <li>{@code reasoningTokens} 是 {@code completionTokens} 的<b>细分项，不是加项</b>
 *       （证据：官方 create-response 示例 22 + 29 = 51，而 reasoning_tokens=27 已含在 29 内）。
 *       所以本记录不提供任何把它们相加的方法，{@link #completionTokens()} 就是输出总量。</li>
 *   <li>{@code promptTokens} 是派生值：官方口径 {@code prompt_tokens = hit + miss}，两者缺一即不可知。</li>
 * </ol>
 *
 * @param rawPayload usage 节点原文，供结算留痕；不含任何凭据
 */
public record ModelUsage(Long promptCacheHitTokens,
                         Long promptCacheMissTokens,
                         Long completionTokens,
                         Long reasoningTokens,
                         JsonNode rawPayload) {

    /** 供应商没给 usage、或整段不可解析时使用：所有分项都是"未知"，不是 0。 */
    public static ModelUsage unknown() {
        return new ModelUsage(null, null, null, null, null);
    }

    /** 只要有任何一个分项存在，就算"这次调用有用量信息"，结算可以按已知部分做（其余仍标未知）。 */
    public boolean hasAnyKnown() {
        return promptCacheHitTokens != null || promptCacheMissTokens != null || completionTokens != null;
    }

    /** 输入侧两个分项都齐备才能算出 prompt 总量；缺一个就整体存疑，不做"按 0 补齐"。 */
    public Long promptTokens() {
        if (promptCacheHitTokens == null || promptCacheMissTokens == null) {
            return null;
        }
        return promptCacheHitTokens + promptCacheMissTokens;
    }

    /** 输入分项是否完整（结算按 (hit×命中价 + miss×未命中价)/1M 需要两者齐备）。 */
    public boolean promptBreakdownKnown() {
        return promptCacheHitTokens != null && promptCacheMissTokens != null;
    }

    public static ModelUsage of(Long hit, Long miss, Long completion, Long reasoning) {
        return new ModelUsage(hit, miss, completion, reasoning, null);
    }

    @Override
    public String toString() {
        return "ModelUsage[hit=" + promptCacheHitTokens + ", miss=" + promptCacheMissTokens
                + ", completion=" + completionTokens + ", reasoning=" + reasoningTokens + "]";
    }
}
