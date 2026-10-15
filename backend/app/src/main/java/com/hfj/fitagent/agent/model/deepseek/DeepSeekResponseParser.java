package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.agent.model.ToolCall;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Chat Completions 响应解析（docs/agent-v1-protocol.md §3、§4、§6）。
 *
 * <p>四条实现纪律：
 * <ol>
 *   <li><b>宽容未知字段</b>：一律按 JsonNode 取值，供应商新增字段不会把正常调用打成失败；</li>
 *   <li><b>{@code arguments} 是 JSON 字符串</b>，原文交给工具执行器解析（官方明确"不保证生成合法 JSON"）；
 *       个别实现返回对象时也接受，转成字符串后语义不变；</li>
 *   <li><b>未识别的 {@code finish_reason} 视为不可用</b>：不猜、不按 stop 处理；</li>
 *   <li><b>不读 {@code reasoning_content}</b>：本轮不实现回传，也不让它进入契约层、轨迹或日志（protocol §5.4）。</li>
 * </ol>
 *
 * <p>usage 缺字段填 null（B10）：把"没回报"写成 0 会让结算把一次已发生的调用记成免费。
 */
@Component
public class DeepSeekResponseParser {

    private static final String REASONING_CONTENT_FIELD = "reasoning_content";

    private final ObjectMapper objectMapper;

    public DeepSeekResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ModelResponse parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null ? "" : body);
        } catch (RuntimeException e) {
            throw malformed("响应体不是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw malformed("响应体不是 JSON 对象", null);
        }
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw malformed("响应缺少 choices[]", null);
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        if (!message.isObject()) {
            throw malformed("响应缺少 choices[0].message", null);
        }

        String content = textField(message, "content");
        List<ToolCall> toolCalls = readToolCalls(message.path("tool_calls"));
        FinishReason finishReason = FinishReason.fromWire(textField(choice, "finish_reason")).orElse(null);
        ModelUsage usage = readUsage(root.path("usage"));
        String providerRequestId = textField(root, "id");
        boolean truncated = finishReason != null && finishReason.isIncomplete();

        return new ModelResponse(content, toolCalls, finishReason, usage, providerRequestId, truncated);
    }

    /** 只用于诊断日志：证明"我们读到了什么形状"，不带正文、不带 reasoning_content。 */
    public String describeShape(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            return "id=" + textField(root, "id")
                    + ", finishReason=" + textField(choice, "finish_reason")
                    + ", toolCalls=" + choice.path("message").path("tool_calls").size()
                    + ", usageKeys=" + usageKeys(root.path("usage"));
        } catch (RuntimeException e) {
            return "unparsable:" + e.getClass().getSimpleName();
        }
    }

    private List<ToolCall> readToolCalls(JsonNode array) {
        List<ToolCall> calls = new ArrayList<>();
        if (array == null || !array.isArray()) {
            return calls;
        }
        for (JsonNode entry : array) {
            String id = textField(entry, "id");
            JsonNode function = entry.path("function");
            String name = textField(function, "name");
            String arguments = rawArguments(function.path("arguments"));
            if (id == null || name == null) {
                // 缺 id 就无法完成配对（tool_call_id 是必填），缺 name 就无法路由：整条丢掉比猜一个强
                continue;
            }
            calls.add(new ToolCall(id, name, arguments));
        }
        return calls;
    }

    /**
     * arguments 允许两种形态：官方规定的 JSON 字符串，以及个别兼容网关直接给对象的情况。
     * 后者序列化成字符串，语义上仍然是"待校验的模型原文"，不在这里解析成业务参数。
     */
    private String rawArguments(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return ToolCall.EMPTY_ARGUMENTS;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        return objectMapper.writeValueAsString(node);
    }

    /**
     * usage 分项（protocol §6）：
     * {@code prompt_cache_hit_tokens} / {@code prompt_cache_miss_tokens} / {@code completion_tokens}
     * / {@code completion_tokens_details.reasoning_tokens}。
     *
     * <p>{@code prompt_tokens} 与 {@code total_tokens} 不读：前者等于 hit+miss（派生值，读了就多一处能写错的地方），
     * 后者对本项目无用。{@code reasoning_tokens} 只作为 completion 的<b>细分</b>记录，不参与任何相加。
     */
    private ModelUsage readUsage(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return ModelUsage.unknown();
        }
        Long hit = longField(usage, "prompt_cache_hit_tokens");
        Long miss = longField(usage, "prompt_cache_miss_tokens");
        Long completion = longField(usage, "completion_tokens");
        Long reasoning = longField(usage.path("completion_tokens_details"), "reasoning_tokens");
        return new ModelUsage(hit, miss, completion, reasoning, usage.deepCopy());
    }

    private static String usageKeys(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return "-";
        }
        List<String> keys = new ArrayList<>();
        usage.properties().forEach(entry -> keys.add(entry.getKey()));
        return String.join(",", keys);
    }

    private static String textField(JsonNode node, String field) {
        if (REASONING_CONTENT_FIELD.equals(field)) {
            throw new IllegalArgumentException("reasoning_content 不得进入契约层（agent-v1-protocol.md §5.4）");
        }
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String raw = value.isString() ? value.stringValue() : value.asString();
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** 缺失/非数字一律 null，<b>不填 0</b>。 */
    private static Long longField(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber() || value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isString()) {
            try {
                return Long.parseLong(value.stringValue().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static ModelProviderException malformed(String detail, Throwable cause) {
        ModelProviderException exception = new ModelProviderException(ProviderErrorKind.RESPONSE_MALFORMED,
                DeepSeekErrorCode.RESPONSE_MALFORMED, detail + "（响应已收到但结构不符合已核实口径）",
                true, null);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    /** 供测试与非流式路径复用：finish_reason 是否在官方 6 值之内。 */
    public static Optional<FinishReason> finishReasonOf(String wire) {
        return FinishReason.fromWire(wire);
    }
}
