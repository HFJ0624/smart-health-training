package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.core.RunCancellation;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP 传输接缝：把"发一次 POST"从 DeepSeekClient 里隔离出来，
 * 好让协议测试能断言<b>真实请求体与请求头</b>，而不是"fake 返回了一个对象"。
 *
 * <p>生产实现是 {@link JdkHttpTransport}（{@code java.net.http.HttpClient}）。
 * 测试实现只需记录 {@link Outbound} 并返回预置的 {@link Inbound}。
 */
public interface DeepSeekTransport {

    /**
     * @param cancellation 用于尽力取消在途请求；请求一旦可能已送达，取消不改变"结果未知"的判定
     * @throws DeepSeekTransportException 传输层失败，携带"是否已发出"判定
     */
    Inbound send(Outbound outbound, RunCancellation cancellation);

    /**
     * 一次外呼的最小描述。
     *
     * <p>{@code headers} 里含 {@code Authorization}，所以 {@link #toString()} 手写并做遮蔽——
     * record 的自动 toString 会把凭据打印进日志，这是本项目明确禁止的（design §5.7）。
     */
    record Outbound(String uri, String jsonBody, Map<String, String> headers, Duration timeout) {

        public static final String AUTHORIZATION = "Authorization";

        public Outbound {
            headers = headers == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        /** 日志/轨迹专用：Authorization 一律遮蔽。 */
        public Map<String, String> sanitizedHeaders() {
            Map<String, String> safe = new LinkedHashMap<>();
            headers.forEach((name, value) -> safe.put(name, AUTHORIZATION.equalsIgnoreCase(name) ? "Bearer [已脱敏]" : value));
            return safe;
        }

        /** 请求体不含凭据（凭据只在头里），但可能含用户输入，所以只报长度。 */
        public int bodyChars() {
            return jsonBody == null ? 0 : jsonBody.length();
        }

        @Override
        public String toString() {
            return "Outbound[uri=" + uri + ", method=POST, headers=" + sanitizedHeaders()
                    + ", bodyChars=" + bodyChars() + ", timeout=" + timeout + "]";
        }
    }

    /** 响应：状态码 + 原文。供应商 4xx 的 body 结构未核实（protocol §9），只按状态码决策。 */
    record Inbound(int statusCode, String body) {

        public boolean successful() {
            return statusCode == 200;
        }

        /** 原文可能含用户数据，日志只报长度。 */
        @Override
        public String toString() {
            return "Inbound[status=" + statusCode + ", bodyChars=" + (body == null ? 0 : body.length()) + "]";
        }
    }
}
