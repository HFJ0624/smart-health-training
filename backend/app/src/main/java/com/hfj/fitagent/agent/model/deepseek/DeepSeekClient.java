package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.model.ModelClient;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.common.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DeepSeek Chat Completions 客户端（{@link ModelClient} 的生产实现）。
 *
 * <p>与协议文档对应的三个关键点：
 * <ol>
 *   <li><b>Key 只在发出前一刻出现</b>：{@link ProviderCredentialResolver#decrypt} 在本方法最后一刻被调用，
 *       明文只进局部变量、只进 Authorization 头，不进日志、不进异常、不进返回值（B10）；</li>
 *   <li><b>超时受 run 剩余时间约束</b>：官方非流式响应会持续返回空行保活、最坏 10 分钟才关连接（protocol §9），
 *       不设上限会拖死 Worker；</li>
 *   <li><b>已发出即不重发</b>：超时/断连/无法理解的响应一律 {@code requestSent=true} → 上层保留不确定预占。
 *       唯一例外是 422（参数非法=服务端在执行前就拒绝）允许的<b>一次</b>有界修正重发。</li>
 * </ol>
 *
 * <p>HTTP 状态映射完全照 protocol §9 的表；供应商 4xx 响应体结构未核实，所以不读它的字段，
 * 也不把它的文案透传给客户端。
 */
@Component
public class DeepSeekClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    /** 422 的有界修正次数：1 次，再多就是拿预算赌运气（design §5、B6.6）。 */
    private static final int MAX_PARAM_REPAIRS = 1;

    private final DeepSeekProperties properties;
    private final DeepSeekRequestFactory requestFactory;
    private final DeepSeekResponseParser responseParser;
    private final ProviderCredentialResolver credentialResolver;
    private final ProviderEndpointResolver endpointResolver;
    private final DeepSeekTransport transport;
    private final AppClock clock;

    @Autowired
    public DeepSeekClient(DeepSeekProperties properties,
                          DeepSeekRequestFactory requestFactory,
                          DeepSeekResponseParser responseParser,
                          ProviderCredentialResolver credentialResolver,
                          AppClock clock,
                          ObjectProvider<ProviderEndpointResolver> endpointResolver) {
        this(properties, requestFactory, responseParser, credentialResolver,
                endpointResolver == null ? null : endpointResolver.getIfAvailable(),
                new JdkHttpTransport(properties), clock);
    }

    /** 测试与手工装配用：传输层可注入。 */
    public DeepSeekClient(DeepSeekProperties properties,
                          DeepSeekRequestFactory requestFactory,
                          DeepSeekResponseParser responseParser,
                          ProviderCredentialResolver credentialResolver,
                          ProviderEndpointResolver endpointResolver,
                          DeepSeekTransport transport,
                          AppClock clock) {
        this.properties = properties;
        this.requestFactory = requestFactory;
        this.responseParser = responseParser;
        this.credentialResolver = credentialResolver;
        this.endpointResolver = endpointResolver;
        this.transport = transport;
        this.clock = clock == null ? new AppClock() : clock;
    }

    @Override
    public String providerId() {
        return DeepSeekRequestFactory.PROVIDER_ID;
    }

    @Override
    public ModelResponse complete(ModelRequest request, AgentContext context) {
        return complete(request, context, new RunCancellation());
    }

    @Override
    public ModelResponse complete(ModelRequest request, AgentContext context, RunCancellation cancellation) {
        if (context == null) {
            throw new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED,
                    DeepSeekErrorCode.PROTOCOL_MISMATCH, "缺少 AgentContext");
        }
        if (!context.hasCredential()) {
            throw new ModelProviderException(ProviderErrorKind.CREDENTIAL_MISSING,
                    DeepSeekErrorCode.CREDENTIAL_MISSING, "本次运行没有绑定可用凭据");
        }
        RunCancellation effectiveCancellation = cancellation == null ? new RunCancellation() : cancellation;
        Duration timeout = boundedTimeout(context);
        String uri = resolveUri(context);
        // 传输超时永远由 run 剩余时间压过一遍，不接受调用方传来的更大值（protocol §9）
        ModelRequest effective = request.withTimeout(timeout);

        DeepSeekTransport.Inbound inbound;
        int repairs = 0;
        while (true) {
            String body = requestFactory.toJson(effective);
            // 明文 Key 到这一行才存在；它只进请求头，不进日志、不进异常、不进返回结构
            String apiKey = resolveCredential(context);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(DeepSeekTransport.Outbound.AUTHORIZATION, "Bearer " + apiKey);

            DeepSeekTransport.Outbound outbound = new DeepSeekTransport.Outbound(uri, body, headers, timeout);
            inbound = sendOrWrap(outbound, effectiveCancellation);
            if (inbound.statusCode() != 422 || repairs >= MAX_PARAM_REPAIRS) {
                break;
            }
            // 422 = 服务端在执行前拒绝参数，不产生推理费用；允许一次"只收紧可选参数"的修正重发
            repairs++;
            log.warn("DeepSeek 422 参数非法，进行唯一一次有界修正重发 attempt={}", repairs);
            effective = repair(effective, context);
        }
        return handle(inbound, context, uri);
    }

    // ---- 内部 ----

    private DeepSeekTransport.Inbound sendOrWrap(DeepSeekTransport.Outbound outbound, RunCancellation cancellation) {
        try {
            return transport.send(outbound, cancellation);
        } catch (DeepSeekTransportException e) {
            throw new ModelProviderException(
                    e.requestSent() ? ProviderErrorKind.TRANSPORT_UNKNOWN : ProviderErrorKind.TRANSPORT_BEFORE_SEND,
                    e.errorCode(), e.getMessage(), e.requestSent(), null);
        }
    }

    private ModelResponse handle(DeepSeekTransport.Inbound inbound, AgentContext context, String uri) {
        int status = inbound.statusCode();
        if (status == 200) {
            return responseParser.parse(inbound.body());
        }
        // 只记状态码与地址：供应商 4xx body 结构未核实（protocol §9），且可能回显请求内容
        log.warn("DeepSeek 非 200 响应 status={} uri={}", status, uri);
        classifyStatus(status, context);
        // classifyStatus 必定抛出；走到这里说明它被改坏了，宁可抛内部错误也不要静默返回 null
        throw new ModelProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE,
                DeepSeekErrorCode.PROVIDER_UNAVAILABLE, "未预期的响应状态 " + status, true, status);
    }

    /** HTTP 状态 → 本项目处置（逐行对照 docs/agent-v1-protocol.md §9 的表）。 */
    private void classifyStatus(int status, AgentContext context) {
        switch (status) {
            case 401 -> {
                // 凭据转 INVALID：此后不允许启动新调用（已在途的追不回来，界面如实说明）
                safelyMarkInvalid(context);
                throw new ModelProviderException(ProviderErrorKind.CREDENTIAL_INVALID,
                        DeepSeekErrorCode.CREDENTIAL_INVALID, "供应商拒绝了凭据", true, status);
            }
            case 402 -> throw new ModelProviderException(ProviderErrorKind.INSUFFICIENT_BALANCE,
                    DeepSeekErrorCode.INSUFFICIENT_BALANCE, "供应商账户余额不足（BYOK 下请向供应商充值）",
                    true, status);
            case 422 -> throw new ModelProviderException(ProviderErrorKind.INVALID_PARAMS,
                    DeepSeekErrorCode.INVALID_PARAMS, "参数非法，且唯一一次有界修正后仍被拒绝", true, status);
            case 400 -> throw new ModelProviderException(ProviderErrorKind.INVALID_REQUEST,
                    DeepSeekErrorCode.INVALID_REQUEST, "请求格式非法，不重发", true, status);
            case 429 -> throw new ModelProviderException(ProviderErrorKind.RATE_LIMITED,
                    DeepSeekErrorCode.RATE_LIMITED, "并发超限（账户级限流），本轮不重发", true, status);
            case 500, 503 -> throw new ModelProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    DeepSeekErrorCode.PROVIDER_UNAVAILABLE, "模型服务不可用", true, status);
            default -> throw new ModelProviderException(ProviderErrorKind.PROVIDER_UNAVAILABLE,
                    DeepSeekErrorCode.PROVIDER_UNAVAILABLE, "未预期的响应状态 " + status, true, status);
        }
    }

    /** 只有 https、无凭据泄漏风险的地址才会走到这里（DeepSeekProperties 构造期已校验）。 */
    private String resolveUri(AgentContext context) {
        String override = null;
        if (endpointResolver != null) {
            try {
                override = endpointResolver.baseUrl(context.model().providerId());
            } catch (RuntimeException e) {
                // 表里读不到地址不是致命错误：回落到配置默认值，但要说出来（运维能立刻看出种子缺失）
                log.warn("ProviderEndpointResolver 取值失败，回落配置默认地址 provider={} type={}",
                        context.model().providerId(), e.getClass().getSimpleName());
            }
        }
        return properties.chatCompletionsUri(override);
    }

    private String resolveCredential(AgentContext context) {
        String apiKey;
        try {
            apiKey = credentialResolver.decrypt(context.credential());
        } catch (RuntimeException e) {
            log.warn("凭据解密失败 type={}", e.getClass().getSimpleName());
            throw new ModelProviderException(ProviderErrorKind.CREDENTIAL_INVALID,
                    DeepSeekErrorCode.CREDENTIAL_MISSING, "凭据无法解密", false, null);
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new ModelProviderException(ProviderErrorKind.CREDENTIAL_MISSING,
                    DeepSeekErrorCode.CREDENTIAL_MISSING, "凭据不存在或已撤销", false, null);
        }
        return apiKey.trim();
    }

    private void safelyMarkInvalid(AgentContext context) {
        if (context.credential() == null) {
            return;
        }
        try {
            credentialResolver.markInvalid(context.credential());
        } catch (RuntimeException e) {
            // 401 的结论不能因为"回写状态失败"被掩盖：凭据无效照样上报
            log.warn("凭据状态回写失败 type={}", e.getClass().getSimpleName());
        }
    }

    /** 传输超时 = min(配置读超时, run 剩余时间)；剩余时间为 0 时根本不发出（省一次必败的预占）。 */
    private Duration boundedTimeout(AgentContext context) {
        Duration remaining = context.remaining(clock.now());
        if (remaining.isZero()) {
            throw new ModelProviderException(ProviderErrorKind.TRANSPORT_BEFORE_SEND,
                    DeepSeekErrorCode.TIMEOUT, "run 已到截止时间，本次调用不发起", false, null);
        }
        Duration configured = properties.readTimeout();
        return configured.compareTo(remaining) <= 0 ? configured : remaining;
    }

    /**
     * 422 的有界修正：<b>只收紧可选参数</b>——去掉 temperature、把 max_tokens 压进目录上限。
     *
     * <p>刻意不做的事：不删 tools（删了就变成"问另一个问题"）、不改消息内容（改了就偏离用户问题）、
     * 不放开 thinking（那是 400 的根源而非解药）。修正后的调用同样计入本 run 的次数与费用。
     */
    private ModelRequest repair(ModelRequest request, AgentContext context) {
        ModelRequest repaired = request.withoutTemperature();
        Integer maxTokens = repaired.maxTokens();
        int catalogCap = context.model().maxOutputTokens();
        if (maxTokens != null && maxTokens > catalogCap) {
            repaired = repaired.withMaxTokens(catalogCap);
        }
        return repaired;
    }
}
