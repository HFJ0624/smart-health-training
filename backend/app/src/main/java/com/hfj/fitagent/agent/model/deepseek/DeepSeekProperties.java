package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.model.ThinkingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * DeepSeek 适配器参数。取值口径全部来自 {@code docs/agent-v1-protocol.md}，不靠记忆。
 *
 * <p>两条会在启动期炸掉的规则：
 * <ol>
 *   <li><b>thinking 必须是 disabled</b>（protocol §5 / design §5.5）。官方默认是 <b>enabled</b>，
 *       而带 {@code tools} 的请求必须完整回传 {@code reasoning_content}，否则**每次工具轮都 400**。
 *       本项目不实现该回传，所以配置层直接拒绝开启——"开着但丢字段"不是可用能力。</li>
 *   <li><b>base-url 不允许以 /v1 结尾</b>（protocol §2）。无凭据探测时带不带 /v1 都返回同一个 401，
 *       证明不了前缀存在；官方文档从未出现 /v1，因此本实现按文档路径拼接，并让"改前缀"必须显式改
 *       {@code chat-completions-path}，避免配置里悄悄写了一个未核实的地址。</li>
 * </ol>
 *
 * <p>base_url 的最终权威值是 {@code model_provider.base_url}（表里由运维写入，接口不接受客户端改写）；
 * 本记录的默认值是"表还没种子时的官方默认地址"，可由 {@link ProviderEndpointResolver} 覆盖。
 *
 * @param maxOutputTokensCap 输出上限的硬顶（protocol §1：max_output_tokens=393216）；只是防止把目录里的错值发出去
 */
@ConfigurationProperties(prefix = "fitagent.agent.model.deepseek")
public record DeepSeekProperties(String baseUrl,
                                 String chatCompletionsPath,
                                 String thinking,
                                 Duration connectTimeout,
                                 Duration readTimeout,
                                 Integer maxOutputTokensCap) {

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    /** 文档路径<b>不带</b> /v1（protocol §2）。 */
    public static final String DEFAULT_CHAT_COMPLETIONS_PATH = "/chat/completions";
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 读取上限 30s：run 总截止默认 120s，取 90s 会让一次挂起吃掉 3/4 预算（协议 §9 的空行保活尤其容易触发）。 */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);
    public static final int DEFAULT_MAX_OUTPUT_TOKENS_CAP = 393216;

    private static final String THINKING_FAILURE = """
            fitagent.agent.model.deepseek.thinking 只能是 disabled，当前配置为 enabled。
            原因（docs/agent-v1-protocol.md §5）：官方默认思考模式是 enabled，而带 tools 的请求必须把
            reasoning_content 完整回传，否则供应商直接返回 400；本项目本轮不实现该回传，
            因此不允许开启思考模式（design §5.5、§14）。要开启，必须先实现并验证 reasoning_content 全量回传。""";

    public DeepSeekProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        chatCompletionsPath = (chatCompletionsPath == null || chatCompletionsPath.isBlank())
                ? DEFAULT_CHAT_COMPLETIONS_PATH
                : chatCompletionsPath.trim();
        if (!chatCompletionsPath.startsWith("/")) {
            chatCompletionsPath = "/" + chatCompletionsPath;
        }
        if (thinking == null || thinking.isBlank()) {
            thinking = ThinkingMode.DISABLED.wireName();
        }
        ThinkingMode mode = ThinkingMode.fromConfig(thinking);
        if (mode != ThinkingMode.DISABLED) {
            throw new IllegalStateException(THINKING_FAILURE);
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            readTimeout = DEFAULT_READ_TIMEOUT;
        }
        if (maxOutputTokensCap == null || maxOutputTokensCap <= 0) {
            maxOutputTokensCap = DEFAULT_MAX_OUTPUT_TOKENS_CAP;
        }
        validateBaseUrl(baseUrl);
    }

    public static DeepSeekProperties defaults() {
        return new DeepSeekProperties(null, null, null, null, null, null);
    }

    /** 解析后的思考模式；恒为 DISABLED（构造期已保证）。 */
    public ThinkingMode thinkingMode() {
        return ThinkingMode.fromConfig(thinking);
    }

    public String chatCompletionsUri() {
        return chatCompletionsUri(baseUrl);
    }

    public String chatCompletionsUri(String overrideBase) {
        String base = (overrideBase == null || overrideBase.isBlank()) ? baseUrl : overrideBase.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        validateBaseUrl(base);
        return base + chatCompletionsPath;
    }

    /** 日志用：只有地址，没有凭据。 */
    @Override
    public String toString() {
        return "DeepSeekProperties[baseUrl=" + baseUrl + ", path=" + chatCompletionsPath
                + ", thinking=" + thinking + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", maxOutputTokensCap=" + maxOutputTokensCap + "]";
    }

    private static void validateBaseUrl(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("fitagent.agent.model.deepseek.base-url 不是合法 URI：" + value);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            // 凭据以 Bearer 头外发：明文 http 等于把 Key 发给链路上任何人（与 model_provider 的 ck 同口径）
            throw new IllegalStateException("fitagent.agent.model.deepseek.base-url 必须是 https，当前：" + value);
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException("base-url 不得包含用户信息、查询串或片段：" + value);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.equals("/v1") || path.endsWith("/v1")) {
            throw new IllegalStateException("DeepSeek 文档路径不带 /v1（docs/agent-v1-protocol.md §2），"
                    + "请把 /v1 写进 fitagent.agent.model.deepseek.chat-completions-path 再显式确认：" + value);
        }
    }
}
