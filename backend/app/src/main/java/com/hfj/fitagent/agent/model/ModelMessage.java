package com.hfj.fitagent.agent.model;

import java.util.List;

/**
 * 进入供应商请求上下文的单条消息（docs/agent-v1-design.md §3）。
 *
 * 三种形态：
 * <ul>
 *   <li>system / user：只有 {@code content}</li>
 *   <li>assistant：{@code content} 或 {@code toolCalls}（可以都为空串/null，但 tool_calls 必须原样保留）</li>
 *   <li>tool：必须带 {@code toolCallId}，指向上一条 assistant 声明过的 id</li>
 * </ul>
 *
 * 供应商特有字段（例如 DeepSeek 的 {@code reasoning_content}）不在此出现：本轮不实现回传，
 * 让它活在 {@code agent.model.deepseek} 内部，契约层就无从把它丢掉或误带（docs/agent-v1-design.md §3）。
 */
public record ModelMessage(MessageRole role,
                           String content,
                           List<ToolCall> toolCalls,
                           String toolCallId,
                           String name) {

    public ModelMessage {
        if (role == null) {
            throw new IllegalArgumentException("消息角色不能为空");
        }
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        content = content == null ? "" : content;
        if (role == MessageRole.TOOL && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("tool 消息必须携带 tool_call_id（agent-v1-protocol.md §3）");
        }
        if (role != MessageRole.TOOL && !toolCalls.isEmpty() && role != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("只有 assistant 消息可以声明 tool_calls");
        }
        if (role == MessageRole.TOOL && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException("tool 消息不得声明 tool_calls");
        }
    }

    public static ModelMessage system(String content) {
        return new ModelMessage(MessageRole.SYSTEM, content, List.of(), null, null);
    }

    public static ModelMessage user(String content) {
        return new ModelMessage(MessageRole.USER, content, List.of(), null, null);
    }

    public static ModelMessage assistant(String content) {
        return new ModelMessage(MessageRole.ASSISTANT, content, List.of(), null, null);
    }

    /** 含 tool_calls 的 assistant 消息：后续请求必须**原样**带上（协议 §3 回传要求）。 */
    public static ModelMessage assistantToolCalls(String content, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("assistant 工具消息必须至少声明一个 tool_call");
        }
        return new ModelMessage(MessageRole.ASSISTANT, content, toolCalls, null, null);
    }

    public static ModelMessage tool(String toolCallId, String name, String content) {
        return new ModelMessage(MessageRole.TOOL, content, List.of(), toolCallId, name);
    }

    public boolean isAssistantToolCallTurn() {
        return role == MessageRole.ASSISTANT && !toolCalls.isEmpty();
    }

    /** 日志友好：角色 + 长度，绝不带正文（正文可能含用户个人数据）。 */
    @Override
    public String toString() {
        return "ModelMessage[role=" + role.wireName() + ", contentChars=" + content.length()
                + ", toolCalls=" + toolCalls.size() + (toolCallId == null ? "" : ", toolCallId=" + toolCallId) + "]";
    }
}
