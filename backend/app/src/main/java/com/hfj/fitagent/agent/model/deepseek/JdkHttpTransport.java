package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.core.RunCancellation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 生产传输实现：JDK {@code java.net.http.HttpClient}（不引入任何 HTTP 抽象库）。
 *
 * <p>三点注意：
 * <ol>
 *   <li><b>不跟随跳转</b>：凭据在 Authorization 头里，一次 302 就能把它送到别的域名
 *       （与 {@code WechatCode2SessionClient} 同一手法）；</li>
 *   <li><b>用 sendAsync + Future.cancel</b>：这样 run 被取消时可以尽力掐掉在途请求。
 *       掐掉之后结果仍然是"未知"——请求可能已经到服务端，所以判定成
 *       {@link DeepSeekErrorCode#RESPONSE_UNKNOWN} 而不是"未发生"；</li>
 *   <li><b>只记异常类型</b>：JDK 的异常文本可能带上完整 URI，日志里出现地址已经够敏感，
 *       更何况任何被拼接进去的头。</li>
 * </ol>
 */
public class JdkHttpTransport implements DeepSeekTransport {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpTransport.class);

    private final HttpClient httpClient;

    public JdkHttpTransport(DeepSeekProperties properties) {
        this(defaultClient(properties));
    }

    /** 测试注入用。 */
    JdkHttpTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    private static HttpClient defaultClient(DeepSeekProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Inbound send(Outbound outbound, RunCancellation cancellation) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(outbound.uri()))
                .POST(HttpRequest.BodyPublishers.ofString(outbound.jsonBody(), StandardCharsets.UTF_8))
                .timeout(outbound.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .build();

        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Runnable cancelAction = () -> future.cancel(true);
        if (cancellation != null) {
            cancellation.attachInFlight(cancelAction);
        }
        try {
            HttpResponse<String> response = future.join();
            return new Inbound(response.statusCode(), response.body());
        } catch (CompletionException e) {
            throw classify(unwrap(e), outbound);
        } catch (CancellationException e) {
            // 取消发生在"已经发出"之后：不能宣称这次调用没花过钱
            log.warn("DeepSeek 请求在途中被取消 uri={}", outbound.uri());
            throw new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN,
                    "请求已在途被取消，结果未知", true, e);
        } finally {
            if (cancellation != null) {
                cancellation.detachInFlight(cancelAction);
            }
        }
    }

    private DeepSeekTransportException classify(Throwable cause, Outbound outbound) {
        if (cause instanceof UnknownHostException || cause instanceof ConnectException
                || cause instanceof NoRouteToHostException) {
            // 可证明未送达：DNS 都没解析出来，或连接根本没建立 → 允许释放预占
            log.warn("DeepSeek 连接未建立 type={}", cause.getClass().getSimpleName());
            return new DeepSeekTransportException(DeepSeekErrorCode.NOT_SENT,
                    "无法连接模型服务", false, cause);
        }
        if (cause instanceof HttpTimeoutException) {
            log.warn("DeepSeek 请求超时 uri={} timeout={}", outbound.uri(), outbound.timeout());
            return new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN,
                    "请求超时，结果未知（官方非流式响应会持续返回空行保活，超时不等同于未送达）", true, cause);
        }
        if (cause instanceof IOException) {
            log.warn("DeepSeek 网络失败 type={}", cause.getClass().getSimpleName());
            return new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN,
                    "连接中断，结果未知", true, cause);
        }
        if (cause instanceof RuntimeException runtimeException) {
            log.warn("DeepSeek 传输异常 type={}", cause.getClass().getSimpleName());
            return new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN,
                    "传输失败", true, runtimeException);
        }
        log.warn("DeepSeek 传输异常（非运行时）type={}", cause.getClass().getSimpleName());
        return new DeepSeekTransportException(DeepSeekErrorCode.RESPONSE_UNKNOWN, "传输失败", true, cause);
    }

    private static Throwable unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        return cause == null ? e : cause;
    }
}
