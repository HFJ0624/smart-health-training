package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.agent.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 响应解析验收：finish_reason 白名单、usage 的"缺字段填 null 不填 0"、reasoning 是细分项、
 * 未知字段宽容、未识别 finish_reason 视为不可用（docs/agent-v1-protocol.md §4、§6）。
 */
class DeepSeekResponseParserTest {

    private final DeepSeekResponseParser parser = new DeepSeekResponseParser(AgentTestSupport.mapper());

    @Test
    @DisplayName("只认官方 6 个 finish_reason 值")
    void enumCoversExactlyTheDocumentedValues() {
        assertThat(FinishReason.values()).hasSize(6);
        assertThat(FinishReason.fromWire("stop")).contains(FinishReason.STOP);
        assertThat(FinishReason.fromWire("tool_calls")).contains(FinishReason.TOOL_CALLS);
        assertThat(FinishReason.fromWire("length")).contains(FinishReason.LENGTH);
        assertThat(FinishReason.fromWire("content_filter")).contains(FinishReason.CONTENT_FILTER);
        assertThat(FinishReason.fromWire("insufficient_system_resource")).contains(FinishReason.INSUFFICIENT_SYSTEM_RESOURCE);
        assertThat(FinishReason.fromWire("aborted")).contains(FinishReason.ABORTED);
        assertThat(FinishReason.fromWire("max_output_reached")).isEmpty();
    }

    @ParameterizedTest(name = "finish_reason={0} → usableAnswer={1}, accepted={2}")
    @CsvSource({
            "stop, true, true",
            "tool_calls, false, true",
            "length, false, false",
            "content_filter, false, false",
            "insufficient_system_resource, false, false",
            "aborted, false, false"
    })
    @DisplayName("isUsableAnswer 只对 stop 为真；只有 stop 与 tool_calls 可接受")
    void finishReasonClassification(String wire, boolean usableAnswer, boolean accepted) {
        FinishReason reason = FinishReason.fromWire(wire).orElseThrow();
        assertThat(reason.isUsableAnswer()).isEqualTo(usableAnswer);
        assertThat(FinishReason.isAccepted(reason)).isEqualTo(accepted);
        assertThat(reason.isIncomplete()).isEqualTo(!accepted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unexpected_value", "", "STOP_UNKNOWN", "toolCall"})
    @DisplayName("未识别的 finish_reason 解析成 null，且不可用（不靠'看起来像'猜）")
    void unrecognisedFinishReasonIsNotUsable(String wire) {
        assertThat(FinishReason.fromWire(wire)).isEmpty();
        String body = """
                {"id":"r","choices":[{"message":{"role":"assistant","content":"半句"},
                  "finish_reason":"%s"}]}
                """.formatted(wire);
        ModelResponse response = parser.parse(body);
        assertThat(response.finishReason()).isNull();
        assertThat(FinishReason.isAccepted(response.finishReason())).isFalse();
        assertThat(response.isUsableAnswer()).isFalse();
    }

    @Test
    @DisplayName("tool_calls[].function.arguments 是 JSON 字符串原文，配对 id 逐字符保留")
    void toolCallArgumentsStayRawStrings() {
        String body = """
                {"id":"chatcmpl-77","choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_a","type":"function",
                    "function":{"name":"get_weight_trend","arguments":"{\\"days\\":7}"}}]},
                  "finish_reason":"tool_calls"}]}
                """;
        ModelResponse response = parser.parse(body);

        assertThat(response.finishReason()).isEqualTo(FinishReason.TOOL_CALLS);
        assertThat(response.isToolCallRound()).isTrue();
        assertThat(response.providerRequestId()).isEqualTo("chatcmpl-77");
        ToolCall call = response.toolCalls().get(0);
        assertThat(call.id()).isEqualTo("call_a");
        assertThat(call.name()).isEqualTo("get_weight_trend");
        assertThat(call.argumentsJson()).isEqualTo("{\"days\":7}");
    }

    @Test
    @DisplayName("length 让响应被标记为截断，tool_calls 因此不可执行")
    void lengthResponseIsTruncated() {
        String body = """
                {"id":"r2","choices":[{"message":{"role":"assistant","content":"写到一半",
                  "tool_calls":[{"id":"c","type":"function","function":{"name":"get_profile","arguments":"{\\"da"}}]},
                  "finish_reason":"length"}]}
                """;
        ModelResponse response = parser.parse(body);

        assertThat(response.truncated()).isTrue();
        assertThat(response.isToolCallRound()).isFalse();
        assertThat(response.hasToolCalls()).isTrue();
    }

    @Test
    @DisplayName("usage 分项按官方字段名读取；缺失分项是 null 而不是 0")
    void usageMissingFieldsAreNullNotZero() {
        ModelResponse full = parser.parse("""
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_cache_hit_tokens":120,"prompt_cache_miss_tokens":80,
                 "completion_tokens":40,"completion_tokens_details":{"reasoning_tokens":15}}}
                """);
        assertThat(full.usage().promptCacheHitTokens()).isEqualTo(120L);
        assertThat(full.usage().promptCacheMissTokens()).isEqualTo(80L);
        assertThat(full.usage().completionTokens()).isEqualTo(40L);
        assertThat(full.usage().reasoningTokens()).isEqualTo(15L);
        // reasoning 是 completion 的细分项：任何"completion + reasoning"的算法都是重复计费
        assertThat(full.usage().promptTokens()).isEqualTo(200L);

        ModelResponse partial = parser.parse("""
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}],
                 "usage":{"completion_tokens":40}}
                """);
        assertThat(partial.usage().completionTokens()).isEqualTo(40L);
        assertThat(partial.usage().promptCacheHitTokens()).isNull();
        assertThat(partial.usage().promptCacheMissTokens()).isNull();
        assertThat(partial.usage().promptTokens()).isNull();
        assertThat(partial.usage().reasoningTokens()).isNull();
        assertThat(partial.usage().hasAnyKnown()).isTrue();

        ModelResponse withoutUsage = parser.parse("""
                {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                """);
        assertThat(withoutUsage.usage().hasAnyKnown()).isFalse();
        assertThat(withoutUsage.usage().completionTokens()).isNull();
    }

    @Test
    @DisplayName("响应里新增的未知字段不影响解析；reasoning_content 不进任何契约字段")
    void unknownFieldsAreToleratedAndReasoningContentDropped() {
        ModelResponse response = parser.parse("""
                {"id":"r3","system_fingerprint":"fp_abc","choices":[{"index":0,
                  "message":{"role":"assistant","content":"结论","reasoning_content":"内部思考原文不得外泄",
                  "vendor_extra":{"nested":true}},"finish_reason":"stop",
                  "logprobs":null,"x_new_field":1}],
                  "usage":{"prompt_cache_hit_tokens":1,"prompt_cache_miss_tokens":2,"completion_tokens":3},
                  "x_top_level":true}
                """);

        assertThat(response.content()).isEqualTo("结论");
        assertThat(response.content()).doesNotContain("内部思考原文");
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    @DisplayName("缺 choices / 非 JSON / 非对象 → 已发送但结果未知，不重发")
    void malformedResponsesAreReportedAsUnknown() {
        for (String body : new String[]{"", "not json", "[]", "{\"object\":\"error\"}",
                "{\"choices\":[]}"}) {
            assertThatThrownBy(() -> parser.parse(body))
                    .as("body=%s", body)
                    .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                        assertThat(e.kind()).isEqualTo(ProviderErrorKind.RESPONSE_MALFORMED);
                        assertThat(e.requestSent()).isTrue();
                        assertThat(e.providerErrorCode()).isEqualTo(DeepSeekErrorCode.RESPONSE_MALFORMED);
                    });
        }
    }

    @Test
    @DisplayName("缺少 id 或 name 的工具调用被丢弃而不是猜一个（猜出来的 id 无法完成协议配对）")
    void incompleteToolCallsAreDropped() {
        ModelResponse response = parser.parse("""
                {"choices":[{"message":{"role":"assistant","content":"",
                  "tool_calls":[{"type":"function","function":{"name":"get_profile","arguments":"{}"}},
                                {"id":"call_ok","type":"function",
                                 "function":{"name":"get_weight_trend","arguments":"{}"}}]},
                  "finish_reason":"tool_calls"}]}
                """);

        assertThat(response.toolCalls()).extracting(ToolCall::id).containsExactly("call_ok");
    }
}
