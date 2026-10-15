package com.hfj.fitagent.agent.model;

import com.hfj.fitagent.agent.tool.ToolDefinition;

import java.time.Duration;
import java.util.List;

/**
 * 一次远程补全的逻辑请求（docs/agent-v1-design.md §3）。
 *
 * <p>这里描述的是"我们想要什么"，不含任何供应商专有字段，也不含凭据——密钥只在真正发出 HTTP 的那一步
 * 由 {@code ProviderCredentialResolver} 解密取得（B10）。
 *
 * @param toolChoice  null 表示<b>不发送该字段</b>（供应商默认：有 tools 时 auto）
 * @param thinking    null 由适配层按"必须显式 disabled"的规则补齐，见 DeepSeekRequestFactory
 * @param maxTokens   输出上限，来自 RunLimits 与 model_catalog 的较小值，不由模型自己决定
 * @param temperature null 表示不发送（非思考参数按需给，protocol §5）
 * @param timeout     本次调用的传输超时上限；实际发出前还会被 run 剩余时间再压一次（protocol §9）
 */
public record ModelRequest(String model,
                           List<ModelMessage> messages,
                           List<ToolDefinition> tools,
                           ToolChoice toolChoice,
                           ThinkingMode thinking,
                           Integer maxTokens,
                           Double temperature,
                           Duration timeout) {

    public ModelRequest {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("ModelRequest.model 不能为空（取值来自 model_catalog）");
        }
        messages = messages == null || messages.isEmpty()
                ? List.of()
                : List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("ModelRequest.messages 不能为空");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        model = model.trim();
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }

    public ModelRequest withToolChoice(ToolChoice choice) {
        return new ModelRequest(model, messages, tools, choice, thinking, maxTokens, temperature, timeout);
    }

    public ModelRequest withMessages(List<ModelMessage> newMessages) {
        return new ModelRequest(model, newMessages, tools, toolChoice, thinking, maxTokens, temperature, timeout);
    }

    /** 422 的有界修正用：只收紧输出上限，不改变语义（不删 tools、不动消息）。 */
    public ModelRequest withMaxTokens(Integer newMaxTokens) {
        return new ModelRequest(model, messages, tools, toolChoice, thinking, newMaxTokens, temperature, timeout);
    }

    /** 422 的有界修正用：非思考参数属可选，去掉它不改变要问的内容。 */
    public ModelRequest withoutTemperature() {
        return new ModelRequest(model, messages, tools, toolChoice, thinking, maxTokens, null, timeout);
    }

    public ModelRequest withTimeout(Duration newTimeout) {
        return new ModelRequest(model, messages, tools, toolChoice, thinking, maxTokens, temperature, newTimeout);
    }

    /**
     * 循环检测用：同一 run 内出现<b>逐字符相同</b>的请求就是空转。
     *
     * <p>这里刻意做精确文本签名而不是哈希：误判成"重复"会把一次合法的回答提前终止（代价比多占一点内存大）。
     * 体积上界由 RunLimits 决定（≤4 次调用、每个工具结果 ≤ maxResultBytes），可接受。
     * 不含 {@code timeout}——它随 run 剩余时间收缩，与"问了同一个问题"无关。
     */
    public String canonicalShape() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(model).append('\n')
                .append(toolChoice).append('\n')
                .append(thinking).append('\n')
                .append(maxTokens).append('\n')
                .append(temperature).append('\n')
                .append(tools.size()).append('\n');
        for (ToolDefinition tool : tools) {
            sb.append(tool.name()).append(',');
        }
        sb.append('\n');
        for (ModelMessage message : messages) {
            sb.append(message.role().wireName()).append('|')
                    .append(message.content()).append('|')
                    .append(message.toolCallId() == null ? "" : message.toolCallId()).append('|')
                    .append(message.name() == null ? "" : message.name()).append('|');
            for (ToolCall call : message.toolCalls()) {
                sb.append(call.id()).append('=').append(call.name()).append('=').append(call.argumentsJson()).append(';');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ModelRequest[model=" + model + ", messages=" + messages.size() + ", tools=" + tools.size()
                + ", toolChoice=" + toolChoice + ", maxTokens=" + maxTokens + "]";
    }
}
