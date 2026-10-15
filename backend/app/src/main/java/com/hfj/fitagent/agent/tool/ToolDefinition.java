package com.hfj.fitagent.agent.tool;

import java.util.regex.Pattern;

/**
 * 暴露给模型的工具声明（docs/agent-v1-protocol.md §3 的 {@code tools[]} 条目）。
 *
 * <p>{@code name} 的约束就是官方对 Chat Completions 的要求：{@code [a-zA-Z0-9_-]}、≤128 字符，
 * 且同一次请求内唯一。把校验放在构造阶段，注册表就永远不可能把一个供应商会当场 400 的名字带出去。
 *
 * @param jsonSchema JSON Schema 文本（对应 {@code function.parameters}）。保存为文本而不是 JsonNode：
 *                   工具实现方大多从注解/常量生成 schema，注册时由 ToolRegistry 用同一个 ObjectMapper 解析一次，
 *                   解析失败即启动失败。本轮<b>不</b>发送 {@code strict}（官方标注 Beta 且需 /beta 前缀）。
 */
public record ToolDefinition(String name, String description, String jsonSchema) {

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,128}$");

    public ToolDefinition {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("工具名必须匹配 ^[a-zA-Z0-9_-]{1,128}$，当前值：" + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具 " + name + " 缺少 description：模型无从判断何时调用");
        }
        if (jsonSchema == null || jsonSchema.isBlank()) {
            throw new IllegalArgumentException("工具 " + name + " 缺少 parameters JSON Schema");
        }
        description = description.trim();
    }

    /** 日志/轨迹友好：只带名字与长度。 */
    @Override
    public String toString() {
        return "ToolDefinition[" + name + ", descriptionChars=" + description.length()
                + ", schemaChars=" + jsonSchema.length() + "]";
    }
}
