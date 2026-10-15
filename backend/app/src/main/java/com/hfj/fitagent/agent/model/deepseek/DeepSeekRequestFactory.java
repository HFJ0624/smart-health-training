package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.model.ModelClient;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.agent.model.ThinkingMode;
import com.hfj.fitagent.agent.model.ToolCall;
import com.hfj.fitagent.agent.model.ToolChoice;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 按 {@code docs/agent-v1-protocol.md} 逐字段构造 DeepSeek Chat Completions 请求体。
 *
 * <p><b>必须出现</b>：{@code model}、{@code messages}、
 * {@code tools[].{type:"function",function:{name,description,parameters}}}、
 * {@code tool_choice}（只在 auto/none 之间）、{@code max_tokens}、
 * 以及<b>每次请求都显式</b> {@code thinking:{type:"disabled"}}。
 *
 * <p><b>绝不出现</b>（每一条都是文档指明的 400 风险或不存在参数）：
 * <ul>
 *   <li>{@code stream} / {@code stream_options}：本轮非流式；官方明确 {@code stream} 非 true 时设置
 *       {@code stream_options} 会 400（protocol §10）；</li>
 *   <li>{@code parallel_tool_calls}：<b>不是</b> Chat Completions 的文档参数，只出现在 Responses API 的输出里
 *       （protocol §3）；</li>
 *   <li>{@code strict}：属 Beta，需要把 base_url 换成 {@code /beta}，且要求全字段 required +
 *       additionalProperties:false（protocol §3）。本轮不用它，服务端参数校验与权限校验照常保留；</li>
 *   <li>{@code tool_choice: required} 或指定工具名：思考模式下 400，本轮连关闭思考也不放开（§3、§5.3）；</li>
 *   <li>{@code reasoning_effort}：只在思考模式有意义，本轮不发送。</li>
 * </ul>
 */
@Component
public class DeepSeekRequestFactory {

    /** protocol §0.1：这两个名字已于 2026-07-24 停用，当前 model 枚举只有 flash 与 v4-pro。 */
    private static final Set<String> RETIRED_MODEL_CODES = Set.of("deepseek-chat", "deepseek-reasoner");

    private final ObjectMapper objectMapper;
    private final DeepSeekProperties properties;

    public DeepSeekRequestFactory(ObjectMapper objectMapper, DeepSeekProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 供应商标识，与 model_provider.provider_code / adapter_code 同口径。 */
    public static final String PROVIDER_ID = "DEEPSEEK";

    public String providerId() {
        return PROVIDER_ID;
    }

    public ObjectNode build(ModelRequest request) {
        if (request == null) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.PROTOCOL_MISMATCH, "请求为空");
        }
        validate(request);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", request.model());

        ArrayNode messages = root.putArray("messages");
        for (ModelMessage message : request.messages()) {
            messages.add(toMessageNode(message));
        }

        if (request.hasTools()) {
            ArrayNode tools = root.putArray("tools");
            for (ToolDefinition definition : request.tools()) {
                tools.add(toToolNode(definition));
            }
        }
        ToolChoice choice = request.toolChoice();
        if (choice != null) {
            root.put("tool_choice", choice.wireName());
        }
        if (request.maxTokens() != null) {
            root.put("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            // 只在非思考路径给（protocol §5：思考模式下会被静默忽略）；本轮思考恒 disabled，所以允许
            root.put("temperature", request.temperature());
        }
        // 显式、无条件：不依赖供应商默认值（默认是 enabled，会让我们每个工具轮都 400）
        root.putObject("thinking").put("type", ThinkingMode.DISABLED.wireName());
        return root;
    }

    public String toJson(ModelRequest request) {
        return objectMapper.writeValueAsString(build(request));
    }

    private ObjectNode toMessageNode(ModelMessage message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", message.role().wireName());
        // content 一律给字符串：assistant 带 tool_calls 时官方示例也是 content + tool_calls 并存，
        // 给空串而不是 null，避免不同实现对 null 的处理差异变成一次真实扣费的 400
        node.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode entry = calls.addObject();
                entry.put("id", call.id());
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", call.name());
                // 原文回传：官方要求"把含 tool_calls 的 assistant 消息原样追加"（protocol §3、design §5.2）
                function.put("arguments", call.argumentsJson());
            }
        }
        if (message.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL) {
            node.put("tool_call_id", message.toolCallId());
            if (message.name() != null && !message.name().isBlank()) {
                node.put("name", message.name());
            }
        }
        return node;
    }

    private ObjectNode toToolNode(ToolDefinition definition) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("type", "function");
        ObjectNode function = entry.putObject("function");
        function.put("name", definition.name());
        function.put("description", definition.description());
        JsonNode parameters;
        try {
            parameters = objectMapper.readTree(definition.jsonSchema());
        } catch (RuntimeException e) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.PROTOCOL_MISMATCH,
                    "工具 " + definition.name() + " 的 parameters 不是合法 JSON");
        }
        if (!parameters.isObject()) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.PROTOCOL_MISMATCH,
                    "工具 " + definition.name() + " 的 parameters 必须是 JSON 对象");
        }
        function.set("parameters", parameters.deepCopy());
        return entry;
    }

    private void validate(ModelRequest request) {
        if (request.thinking() != null && request.thinking() != ThinkingMode.DISABLED) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.THINKING_MODE_FORBIDDEN,
                    "本轮不允许开启思考模式：未实现 reasoning_content 回传，带 tools 的请求会 400"
                            + "（docs/agent-v1-protocol.md §5）");
        }
        String normalizedModel = request.model().toLowerCase(Locale.ROOT);
        if (RETIRED_MODEL_CODES.contains(normalizedModel)) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.MODEL_CODE_RETIRED,
                    "模型 " + normalizedModel + " 已于 2026-07-24 停用（docs/agent-v1-protocol.md §0.1）");
        }
        if (request.maxTokens() != null && request.maxTokens() > properties.maxOutputTokensCap()) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.MAX_TOKENS_OUT_OF_RANGE,
                    "max_tokens " + request.maxTokens() + " 超过官方输出硬顶 "
                            + properties.maxOutputTokensCap() + "（docs/agent-v1-protocol.md §1）");
        }
        Set<String> toolNames = new LinkedHashSet<>();
        for (ToolDefinition definition : request.tools()) {
            if (!toolNames.add(definition.name())) {
                throw new ModelProviderException(ProviderErrorKind.PROTOCOL_MISMATCH,
                        DeepSeekErrorCode.PROTOCOL_MISMATCH,
                        "同一次请求内工具名必须唯一，重复：" + definition.name() + "（protocol §3）");
            }
        }
        assertToolPairing(request);
    }

    /**
     * 发出前的最后一道配对检查：孤儿 tool 消息 / 未闭合工具组都会让供应商当场 400，
     * 而且是在<b>已经预占预算之后</b>——所以宁可在这里失败，也绝不把非法上下文发出去。
     */
    private void assertToolPairing(ModelRequest request) {
        Set<String> openIds = null;
        Set<String> pending = null;
        for (ModelMessage message : request.messages()) {
            if (message.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL) {
                if (openIds == null || pending == null || !openIds.contains(message.toolCallId())
                        || !pending.remove(message.toolCallId())) {
                    throw new ModelProviderException(ProviderErrorKind.PROTOCOL_MISMATCH,
                            DeepSeekErrorCode.PROTOCOL_MISMATCH,
                            "tool 消息与最近的 assistant tool_calls 不配对：" + message.toolCallId());
                }
                continue;
            }
            if (pending != null && !pending.isEmpty()) {
                throw new ModelProviderException(ProviderErrorKind.PROTOCOL_MISMATCH,
                        DeepSeekErrorCode.PROTOCOL_MISMATCH,
                        "存在未闭合的工具组，不能发出请求");
            }
            if (message.isAssistantToolCallTurn()) {
                Set<String> ids = new LinkedHashSet<>();
                message.toolCalls().forEach(call -> ids.add(call.id()));
                openIds = ids;
                pending = new LinkedHashSet<>(ids);
            } else {
                openIds = null;
                pending = null;
            }
        }
        if (pending != null && !pending.isEmpty()) {
            throw new ModelProviderException(ProviderErrorKind.PROTOCOL_MISMATCH,
                    DeepSeekErrorCode.PROTOCOL_MISMATCH, "上下文以未闭合的工具组结尾，不能发出请求");
        }
    }
}
