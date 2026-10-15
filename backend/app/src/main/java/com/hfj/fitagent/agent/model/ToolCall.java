package com.hfj.fitagent.agent.model;

/**
 * 模型声明的一次工具调用。
 *
 * {@code argumentsJson} 保持**供应商原文字符串**：官方明确"模型不保证生成合法 JSON，请在你的代码里校验"
 * （docs/agent-v1-protocol.md §3），所以这里不做任何"顺手修一下"的规范化——修过的原文再回传给供应商就不再是
 * 原样追加（§5.2），配对语义会失真。解析与校验发生在 {@code agent.tool.ToolExecutor}。
 *
 * @param id            tool_call_id，后续 tool 消息必须逐字符引用它
 * @param name          工具名，必须能在 ToolRegistry 的白名单里找到
 * @param argumentsJson JSON 字符串（不是对象）
 */
public record ToolCall(String id, String name, String argumentsJson) {

    /** 没有参数时供应商可能给 null / 空串，统一按空对象处理，避免把"没参数"当成"参数损坏"。 */
    public static final String EMPTY_ARGUMENTS = "{}";

    public ToolCall {
        id = require(id, "tool_call id 不能为空");
        name = require(name, "tool_call name 不能为空");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? EMPTY_ARGUMENTS : argumentsJson;
    }

    private static String require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    /** 只用于日志与轨迹：证明有调用，但不带参数内容（参数可能含用户输入）。 */
    @Override
    public String toString() {
        return "ToolCall[id=" + id + ", name=" + name + ", argumentsChars=" + argumentsJson.length() + "]";
    }
}
