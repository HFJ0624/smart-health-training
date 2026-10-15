package com.hfj.fitagent.agent.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 官方 {@code finish_reason} 全枚举（docs/agent-v1-protocol.md §4），一个不多一个不少。
 *
 * 只有 {@code stop}（可用回答）与 {@code tool_calls}（进入工具执行）算"可用"；
 * {@code length} / {@code content_filter} / {@code insufficient_system_resource} / {@code aborted}
 * 一律按不完整处理，且<b>不得执行被截断的 arguments</b>。
 * 后两值是否已计费、能否安全重试官方没有说明（protocol §4 "未能核实"），
 * 因此调用方把它们当"已发送、结果未知"，保留不确定预占、不自动重发。
 */
public enum FinishReason {

    /** 自然停止：唯一可以直接作为回答结束的状态。 */
    STOP("stop", true, false),
    /** 模型请求调用工具：进入工具执行，不算回答完成。 */
    TOOL_CALLS("tool_calls", false, true),
    /** 触达 max_tokens：内容可能被截断。 */
    LENGTH("length", false, false),
    /** 内容被过滤。 */
    CONTENT_FILTER("content_filter", false, false),
    /** 推理侧资源不足被中断。 */
    INSUFFICIENT_SYSTEM_RESOURCE("insufficient_system_resource", false, false),
    /** 生成被中断。 */
    ABORTED("aborted", false, false);

    private final String wireName;
    private final boolean usableAnswer;
    private final boolean toolCallRequest;

    FinishReason(String wireName, boolean usableAnswer, boolean toolCallRequest) {
        this.wireName = wireName;
        this.usableAnswer = usableAnswer;
        this.toolCallRequest = toolCallRequest;
    }

    public String wireName() {
        return wireName;
    }

    /** 只有 {@code stop} 为真：这是"可以宣称本轮回答完成"的唯一条件。 */
    public boolean isUsableAnswer() {
        return usableAnswer;
    }

    public boolean isToolCallRequest() {
        return toolCallRequest;
    }

    /** {@code stop} 与 {@code tool_calls} 之外的值都归到这里：内容或协议不可信。 */
    public boolean isIncomplete() {
        return !(usableAnswer || toolCallRequest);
    }

    /**
     * null（缺失或未识别）也算不可用：协议只给了这 6 个值，出现别的值说明我们对响应的理解已经过期，
     * 不能靠"看起来像 stop"猜。
     */
    public static boolean isAccepted(FinishReason reason) {
        return reason != null && !reason.isIncomplete();
    }

    public static Optional<FinishReason> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (FinishReason reason : values()) {
            if (reason.wireName.equals(normalized)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
