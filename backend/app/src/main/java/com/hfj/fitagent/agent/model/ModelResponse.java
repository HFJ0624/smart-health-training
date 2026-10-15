package com.hfj.fitagent.agent.model;

import java.util.List;

/**
 * 供应商一次补全的结构化结果（docs/agent-v1-design.md §3）。
 *
 * {@code finishReason} 可以为 null——官方枚举之外的值或缺失都视为不可用，
 * 由 Runner 映射成 {@code PROTOCOL_INCOMPLETE}，不在这里伪装成 {@code stop}。
 *
 * @param truncated 由解析器根据 {@code length} 等不完整状态置位，表示内容或工具参数可能被截断
 */
public record ModelResponse(String content,
                            List<ToolCall> toolCalls,
                            FinishReason finishReason,
                            ModelUsage usage,
                            String providerRequestId,
                            boolean truncated) {

    public ModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        content = content == null ? "" : content;
        usage = usage == null ? ModelUsage.unknown() : usage;
        truncated = truncated || (finishReason != null && finishReason == FinishReason.LENGTH);
    }

    /** 便捷构造：一轮纯文本回答（测试与 Fake 用）。 */
    public static ModelResponse text(String content) {
        return new ModelResponse(content, List.of(), FinishReason.STOP, ModelUsage.unknown(), null, false);
    }

    public static ModelResponse toolCalls(List<ToolCall> calls) {
        return new ModelResponse("", calls, FinishReason.TOOL_CALLS, ModelUsage.unknown(), null, false);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    /** 可作为回答结束。 */
    public boolean isUsableAnswer() {
        return finishReason != null && finishReason.isUsableAnswer() && !truncated;
    }

    /** 可作为工具轮次继续。被截断的工具调用<b>绝不</b>进入执行（协议 §4）。 */
    public boolean isToolCallRound() {
        return finishReason != null && finishReason.isToolCallRequest() && !truncated && hasToolCalls();
    }

    /** 日志友好：不带正文。 */
    @Override
    public String toString() {
        return "ModelResponse[finishReason=" + finishReason + ", contentChars=" + content.length()
                + ", toolCalls=" + toolCalls.size() + ", truncated=" + truncated
                + (providerRequestId == null ? "" : ", providerRequestId=" + providerRequestId) + "]";
    }
}
