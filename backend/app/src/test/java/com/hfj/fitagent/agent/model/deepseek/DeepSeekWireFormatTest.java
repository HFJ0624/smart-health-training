package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.CredentialHandle;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.agent.model.ThinkingMode;
import com.hfj.fitagent.agent.model.ToolCall;
import com.hfj.fitagent.agent.model.ToolChoice;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 线格式与状态映射：断言的是<b>真实发出去的 JSON 与请求头</b>，而不是"某个 fake 返回了对象"。
 *
 * <p>用可注入的 {@link DeepSeekTransport} 记录 {@link DeepSeekTransport.Outbound}，
 * 这样 protocol §2/§3/§5/§9/§10 的每一条"必须/不得"都能被逐条证明。
 */
class DeepSeekWireFormatTest {

    private static final String OK_BODY = """
            {"id":"req-123","object":"chat.completion",
             "choices":[{"index":0,"message":{"role":"assistant","content":"好了","tool_calls":[]},
             "finish_reason":"stop"}],
             "usage":{"prompt_cache_hit_tokens":100,"prompt_cache_miss_tokens":400,
             "completion_tokens":50,"completion_tokens_details":{"reasoning_tokens":0},
             "prompt_tokens":500,"total_tokens":550}}
            """;

    private final ObjectMapper mapper = AgentTestSupport.mapper();
    private final DeepSeekProperties properties = DeepSeekProperties.defaults();
    private final RecordingTransport transport = new RecordingTransport();
    private final RecordingCredentialResolver credentials = new RecordingCredentialResolver();
    private final DeepSeekClient client = new DeepSeekClient(properties,
            new DeepSeekRequestFactory(mapper, properties),
            new DeepSeekResponseParser(mapper),
            credentials, null, transport, AgentTestSupport.clock());

    private static ModelRequest request(ToolChoice choice, int maxTokens) {
        return new ModelRequest("deepseek-flash",
                List.of(ModelMessage.system("规则"), ModelMessage.user("我最近体重多少？")),
                List.of(new ToolDefinition("get_weight_trend", "读取本人最近体重",
                        "{\"type\":\"object\",\"properties\":{\"days\":{\"type\":\"integer\"}},"
                                + "\"required\":[\"days\"]}")),
                choice, ThinkingMode.DISABLED, maxTokens, null, Duration.ofSeconds(30));
    }

    private AgentContext context() {
        return AgentTestSupport.context(RunLimits.defaults());
    }

    // ---- 6. 请求体：thinking 显式关闭、/v1 不在路径里、禁发字段一个都不出现 ----

    @Test
    @DisplayName("请求体显式带 thinking.type=disabled，且不含 stream / stream_options / parallel_tool_calls")
    void requestBodyMatchesProtocolExactly() {
        client.complete(request(ToolChoice.AUTO, 1024), context());

        DeepSeekTransport.Outbound outbound = transport.sent.get(0);
        JsonNode body = mapper.readTree(outbound.jsonBody());
        assertThat(body.path("model").stringValue()).isEqualTo("deepseek-flash");
        assertThat(body.path("thinking").path("type").stringValue()).isEqualTo("disabled");
        assertThat(body.path("messages").isArray()).isTrue();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(body.path("tool_choice").stringValue()).isEqualTo("auto");

        // 文档明确不存在的参数：出现一次就可能换回一个 400，而且已经预占过预算
        assertThat(body.has("stream")).isFalse();
        assertThat(body.has("stream_options")).isFalse();
        assertThat(body.has("parallel_tool_calls")).isFalse();
        assertThat(body.has("strict")).isFalse();
        assertThat(body.has("reasoning_effort")).isFalse();
        assertThat(body.has("n")).isFalse();
    }

    @Test
    @DisplayName("路径不带 /v1（官方文档从未出现该前缀，探测也无法证实）")
    void pathDoesNotContainV1() {
        client.complete(request(null, 1024), context());

        String uri = transport.sent.get(0).uri();
        assertThat(uri).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(uri).doesNotContain("/v1");
        // tool_choice 未指定时字段省略，而不是发送 null
        assertThat(mapper.readTree(transport.sent.get(0).jsonBody()).has("tool_choice")).isFalse();
    }

    @Test
    @DisplayName("tools[] 形状：type=function + function{name,description,parameters}，parameters 是对象不是字符串")
    void toolDeclarationsUseDocumentedShape() {
        client.complete(request(ToolChoice.AUTO, 1024), context());
        JsonNode tools = mapper.readTree(transport.sent.get(0).jsonBody()).path("tools");

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).path("type").stringValue()).isEqualTo("function");
        assertThat(tools.get(0).path("function").path("name").stringValue()).isEqualTo("get_weight_trend");
        assertThat(tools.get(0).path("function").path("description").stringValue()).isNotBlank();
        assertThat(tools.get(0).path("function").path("parameters").isObject()).isTrue();
        assertThat(tools.get(0).path("function").path("parameters").path("properties").path("days").isObject()).isTrue();
    }

    @Test
    @DisplayName("含 tool_calls 的 assistant 消息原样回传，arguments 保持 JSON 字符串")
    void assistantToolCallsAreEchoedVerbatim() {
        List<ModelMessage> messages = List.of(
                ModelMessage.system("规则"),
                ModelMessage.user("我最近体重多少？"),
                ModelMessage.assistantToolCalls("", List.of(new ToolCall("call-9", "get_weight_trend",
                        "{\"days\":7}"))),
                ModelMessage.tool("call-9", "get_weight_trend", "{\"code\":\"OK\"}"));
        client.complete(new ModelRequest("deepseek-flash", messages, List.of(), ToolChoice.AUTO,
                ThinkingMode.DISABLED, 1024, null, Duration.ofSeconds(30)), context());

        JsonNode echoed = mapper.readTree(transport.sent.get(0).jsonBody()).path("messages").get(2);
        assertThat(echoed.path("role").stringValue()).isEqualTo("assistant");
        assertThat(echoed.path("tool_calls").get(0).path("id").stringValue()).isEqualTo("call-9");
        assertThat(echoed.path("tool_calls").get(0).path("type").stringValue()).isEqualTo("function");
        JsonNode arguments = echoed.path("tool_calls").get(0).path("function").path("arguments");
        assertThat(arguments.isString()).as("arguments 必须是 JSON 字符串而不是对象").isTrue();
        assertThat(arguments.stringValue()).isEqualTo("{\"days\":7}");

        JsonNode toolMessage = mapper.readTree(transport.sent.get(0).jsonBody()).path("messages").get(3);
        assertThat(toolMessage.path("role").stringValue()).isEqualTo("tool");
        assertThat(toolMessage.path("tool_call_id").stringValue()).isEqualTo("call-9");
    }

    @Test
    @DisplayName("tool_choice 只允许 auto/none：required 与指定工具名在类型层就不可表示")
    void toolChoiceOnlyAutoOrNone() {
        assertThat(ToolChoice.values()).extracting(ToolChoice::wireName).containsExactly("auto", "none");
    }

    @Test
    @DisplayName("思考模式一旦被打开就拒绝发出请求（配置之外的第二道锁）")
    void thinkingEnabledRequestIsRefused() {
        // 契约层可以表示 ENABLED（枚举里有该常量），但适配器必须拒绝真的把它发出去
        ModelRequest dangerous = new ModelRequest("deepseek-flash",
                List.of(ModelMessage.system("规则"), ModelMessage.user("我最近体重多少？")),
                request(ToolChoice.AUTO, 1024).tools(),
                ToolChoice.AUTO, ThinkingMode.ENABLED, 1024, null, Duration.ofSeconds(30));

        assertThatThrownBy(() -> client.complete(dangerous, context()))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.CONFIGURATION_REJECTED);
                    assertThat(e.providerErrorCode()).isEqualTo(DeepSeekErrorCode.THINKING_MODE_FORBIDDEN);
                    assertThat(e.getMessage()).contains("reasoning_content");
                });
        assertThat(transport.sent).as("被拒绝的请求一次都不该发出").isEmpty();
    }

    @Test
    @DisplayName("已停用的模型名不允许出现在请求里（2026-07-24 停用，protocol §0.1）")
    void retiredModelCodeIsRefused() {
        ModelRequest retired = new ModelRequest("deepseek-chat", List.of(ModelMessage.user("x")), List.of(),
                null, ThinkingMode.DISABLED, 1024, null, Duration.ofSeconds(30));

        assertThatThrownBy(() -> client.complete(retired, context()))
                .isInstanceOfSatisfying(ModelProviderException.class,
                        e -> assertThat(e.providerErrorCode()).isEqualTo(DeepSeekErrorCode.MODEL_CODE_RETIRED));
    }

    @Test
    @DisplayName("未闭合的工具组不发出请求（省一次必然的 400 与一笔真实扣费）")
    void orphanToolMessageIsRefusedBeforeSend() {
        List<ModelMessage> broken = List.of(ModelMessage.system("规则"),
                ModelMessage.tool("does-not-exist", "get_weight_trend", "{}"));

        assertThatThrownBy(() -> client.complete(new ModelRequest("deepseek-flash", broken, List.of(),
                ToolChoice.AUTO, ThinkingMode.DISABLED, 1024, null, Duration.ofSeconds(30)), context()))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.PROTOCOL_MISMATCH);
                    assertThat(e.requestSent()).isFalse();
                });
        assertThat(transport.sent).isEmpty();
    }

    // ---- 7. 凭据只在发出前一刻取，且不进日志 ----

    @Test
    @DisplayName("Authorization 头带 Bearer，但 Outbound.toString 与任何日志都不出现密钥原文")
    void authorizationHeaderIsPresentButNeverLogged() {
        client.complete(request(ToolChoice.AUTO, 1024), context());

        DeepSeekTransport.Outbound outbound = transport.sent.get(0);
        assertThat(outbound.headers().get("Authorization")).isEqualTo("Bearer " + AgentTestSupport.FAKE_API_KEY);
        assertThat(outbound.sanitizedHeaders().get("Authorization")).doesNotContain(AgentTestSupport.FAKE_API_KEY);
        assertThat(outbound.toString()).doesNotContain(AgentTestSupport.FAKE_API_KEY);
        assertThat(mapper.readTree(outbound.jsonBody()).toString()).doesNotContain(AgentTestSupport.FAKE_API_KEY);
        // 凭据是在发送阶段才解密的，而不是装配/构造时
        assertThat(credentials.decryptCalls).isEqualTo(1);
    }

    // ---- 8/9. 状态映射与"已发送不重发" ----

    @Test
    @DisplayName("401 → CREDENTIAL_INVALID，凭据被标记失效，且不重发、不泄露密钥")
    void unauthorizedMapsToCredentialInvalidAndMarksCredential() {
        transport.canned.add(new DeepSeekTransport.Inbound(401, "{\"error\":{\"message\":\"Authentication Fails\"}}"));
        RunCancellation cancellation = new RunCancellation();

        assertThatThrownBy(() -> client.complete(request(ToolChoice.AUTO, 1024), context(), cancellation))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.CREDENTIAL_INVALID);
                    assertThat(e.providerErrorCode()).isEqualTo(DeepSeekErrorCode.CREDENTIAL_INVALID);
                    assertThat(e.httpStatus()).isEqualTo(401);
                    assertThat(e.requestSent()).isTrue();
                    assertThat(e.getMessage()).doesNotContain(AgentTestSupport.FAKE_API_KEY);
                });
        assertThat(transport.sent).hasSize(1);
        assertThat(credentials.invalidated).containsExactly(AgentTestSupport.credential());
    }

    @Test
    @DisplayName("402/429/400/500/503 各自的码，一律不重发")
    void statusCodesMapToDocumentedKinds() {
        record Case(int status, ProviderErrorKind kind, String code) {
        }
        for (Case testCase : List.of(
                new Case(402, ProviderErrorKind.INSUFFICIENT_BALANCE, DeepSeekErrorCode.INSUFFICIENT_BALANCE),
                new Case(429, ProviderErrorKind.RATE_LIMITED, DeepSeekErrorCode.RATE_LIMITED),
                new Case(400, ProviderErrorKind.INVALID_REQUEST, DeepSeekErrorCode.INVALID_REQUEST),
                new Case(500, ProviderErrorKind.PROVIDER_UNAVAILABLE, DeepSeekErrorCode.PROVIDER_UNAVAILABLE),
                new Case(503, ProviderErrorKind.PROVIDER_UNAVAILABLE, DeepSeekErrorCode.PROVIDER_UNAVAILABLE))) {
            RecordingTransport perCase = new RecordingTransport();
            perCase.canned.add(new DeepSeekTransport.Inbound(testCase.status(), "供应商文案不可信，只按状态码处置"));
            DeepSeekClient scoped = newClient(perCase);

            assertThatThrownBy(() -> scoped.complete(request(ToolChoice.AUTO, 1024), context()))
                    .as("HTTP %s", testCase.status())
                    .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                        assertThat(e.kind()).isEqualTo(testCase.kind());
                        assertThat(e.providerErrorCode()).isEqualTo(testCase.code());
                        assertThat(e.requestSent()).isTrue();
                    });
            assertThat(perCase.sent).as("非 2xx 不自动重发").hasSize(1);
        }
    }

    @Test
    @DisplayName("422 → 唯一一次有界修正后重发；仍失败就报错，不再试第三次")
    void invalidParametersAllowExactlyOneBoundedRepair() {
        RecordingTransport scoped = new RecordingTransport();
        scoped.canned.add(new DeepSeekTransport.Inbound(422, "Invalid Parameters"));
        scoped.canned.add(new DeepSeekTransport.Inbound(422, "Invalid Parameters"));
        DeepSeekClient client = newClient(scoped);

        assertThatThrownBy(() -> client.complete(request(ToolChoice.AUTO, 1024), context()))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.INVALID_PARAMS);
                    assertThat(e.httpStatus()).isEqualTo(422);
                });
        assertThat(scoped.sent).hasSize(2);
        JsonNode first = mapper.readTree(scoped.sent.get(0).jsonBody());
        JsonNode repaired = mapper.readTree(scoped.sent.get(1).jsonBody());
        // 修正是"收紧可选参数"，不是改问题：messages 与 tools 必须一字不变
        assertThat(repaired.path("messages").toString()).isEqualTo(first.path("messages").toString());
        assertThat(repaired.path("tools").toString()).isEqualTo(first.path("tools").toString());
        assertThat(repaired.path("thinking").path("type").stringValue()).isEqualTo("disabled");
    }

    @Test
    @DisplayName("已发出后的超时/断连记为 UNKNOWN 且不重发（B6.8：可能已计费）")
    void postSendTimeoutIsUnknownAndNotRetried() {
        RecordingTransport scoped = new RecordingTransport();
        scoped.failures.add(new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN,
                "请求超时，结果未知", true, null));
        DeepSeekClient client = newClient(scoped);

        assertThatThrownBy(() -> client.complete(request(ToolChoice.AUTO, 1024), context()))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.TRANSPORT_UNKNOWN);
                    assertThat(e.requestSent()).isTrue();
                    assertThat(e.resultUnknown()).isTrue();
                });
        assertThat(scoped.sent).hasSize(1);
    }

    @Test
    @DisplayName("可证明未送达（连接被拒）才标记为 NOT_SENT，允许上层释放预占")
    void preSendFailureIsProvablyNotSent() {
        RecordingTransport scoped = new RecordingTransport();
        scoped.failures.add(new DeepSeekTransportException(DeepSeekErrorCode.NOT_SENT, "无法连接", false, null));

        assertThatThrownBy(() -> newClient(scoped).complete(request(ToolChoice.AUTO, 1024), context()))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.TRANSPORT_BEFORE_SEND);
                    assertThat(e.requestSent()).isFalse();
                });
    }

    @Test
    @DisplayName("200 但响应体读不懂：按已发送、结果未知处理，不重发")
    void malformedSuccessBodyIsUnknown() {
        RecordingTransport scoped = new RecordingTransport();
        scoped.canned.add(new DeepSeekTransport.Inbound(200, "<html>网关返回了页面</html>"));

        assertThatThrownBy(() -> newClient(scoped).complete(request(ToolChoice.AUTO, 1024), context()))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.RESPONSE_MALFORMED);
                    assertThat(e.requestSent()).isTrue();
                });
        assertThat(scoped.sent).hasSize(1);
    }

    @Test
    @DisplayName("HTTP 超时被 run 剩余时间压住；已截止则根本不发出")
    void readTimeoutIsBoundedByRemainingRunTime() {
        DeepSeekTransport.Outbound normal = transportFor(OK_BODY).sent.get(0);
        assertThat(normal.timeout()).isEqualTo(Duration.ofSeconds(30));

        RecordingTransport nearDeadline = new RecordingTransport();
        nearDeadline.canned.add(new DeepSeekTransport.Inbound(200, OK_BODY));
        AgentContext almostExpired = contextNearlyExpired(Duration.ofSeconds(3));
        newClient(nearDeadline).complete(request(ToolChoice.AUTO, 1024), almostExpired);
        assertThat(nearDeadline.sent.get(0).timeout()).isEqualTo(Duration.ofSeconds(3));

        RecordingTransport expired = new RecordingTransport();
        assertThatThrownBy(() -> newClient(expired).complete(request(ToolChoice.AUTO, 1024),
                contextNearlyExpired(Duration.ZERO)))
                .isInstanceOfSatisfying(ModelProviderException.class, e -> {
                    assertThat(e.kind()).isEqualTo(ProviderErrorKind.TRANSPORT_BEFORE_SEND);
                    assertThat(e.providerErrorCode()).isEqualTo(DeepSeekErrorCode.TIMEOUT);
                });
        assertThat(expired.sent).isEmpty();
    }

    @Test
    @DisplayName("成功响应被解析成 finishReason/usage/toolCalls 的正确形状")
    void successBodyIsParsedIntoContract() {
        // 本方法要验的是"完整形状"，所以显式喂带 usage 全字段的 OK_BODY；
        // RecordingTransport 的兜底体 OK_BODY_STATIC 只有最小字段，留给其它测试断言请求形状用。
        transport.canned.add(new DeepSeekTransport.Inbound(200, OK_BODY));
        ModelResponse response = client.complete(request(ToolChoice.AUTO, 1024), context());

        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(response.isUsableAnswer()).isTrue();
        assertThat(response.providerRequestId()).isEqualTo("req-123");
        assertThat(response.usage().promptCacheHitTokens()).isEqualTo(100L);
        assertThat(response.usage().promptCacheMissTokens()).isEqualTo(400L);
        assertThat(response.usage().completionTokens()).isEqualTo(50L);
        assertThat(response.usage().promptTokens()).isEqualTo(500L);
    }

    // ---- 夹具 ----

    private DeepSeekClient newClient(DeepSeekTransport transport) {
        return new DeepSeekClient(properties, new DeepSeekRequestFactory(mapper, properties),
                new DeepSeekResponseParser(mapper), credentials, null, transport, AgentTestSupport.clock());
    }

    private RecordingTransport transportFor(String body) {
        RecordingTransport scoped = new RecordingTransport();
        scoped.canned.add(new DeepSeekTransport.Inbound(200, body));
        newClient(scoped).complete(request(ToolChoice.AUTO, 1024), context());
        return scoped;
    }

    private AgentContext contextNearlyExpired(Duration remaining) {
        AgentContext base = context();
        return new AgentContext(base.runId(), base.runUuid(), base.userId(), base.conversationId(), base.zone(),
                base.businessDate(), base.dataRevision(), base.credential(), base.model(), base.budget(),
                AgentTestSupport.NOW.plus(remaining), base.allowedTools());
    }

    static final class RecordingTransport implements DeepSeekTransport {

        final List<Outbound> sent = new ArrayList<>();
        final Deque<Inbound> canned = new ArrayDeque<>();
        final Deque<RuntimeException> failures = new ArrayDeque<>();

        @Override
        public Inbound send(Outbound outbound, RunCancellation cancellation) {
            sent.add(outbound);
            if (!failures.isEmpty()) {
                throw failures.removeFirst();
            }
            return canned.isEmpty() ? new Inbound(200, OK_BODY_STATIC) : canned.removeFirst();
        }
    }

    /** 默认 200 响应体（字段名与 protocol §4/§6 一致）。 */
    private static final String OK_BODY_STATIC = """
            {"id":"req-static","choices":[{"message":{"role":"assistant","content":"ok"},
            "finish_reason":"stop"}],
            "usage":{"prompt_cache_hit_tokens":1,"prompt_cache_miss_tokens":2,"completion_tokens":3}}
            """;

    static final class RecordingCredentialResolver implements ProviderCredentialResolver {

        int decryptCalls;
        final List<CredentialHandle> invalidated = new ArrayList<>();

        @Override
        public String decrypt(CredentialHandle handle) {
            decryptCalls++;
            return AgentTestSupport.FAKE_API_KEY;
        }

        @Override
        public void markInvalid(CredentialHandle handle) {
            invalidated.add(handle);
        }
    }
}
