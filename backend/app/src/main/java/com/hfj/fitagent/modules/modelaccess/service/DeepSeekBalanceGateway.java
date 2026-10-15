package com.hfj.fitagent.modules.modelaccess.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.ModelAccessProperties;
import com.hfj.fitagent.modules.modelaccess.Vocabulary;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商余额外呼：{@code GET {base_url}{path}}，path 默认 {@code /user/balance}（<b>不带</b> /v1，协议 §2/§8）。
 *
 * <p>地址来源与 code2Session 同一手法：base_url 只能来自 {@code model_provider} 白名单，
 * path 只能来自配置，任何请求参数都不参与拼接——否则服务端就成了 SSRF 跳板。
 *
 * <p>凭据处理三条：
 * <ol>
 *   <li>{@code Authorization: Bearer <明文>}——明文只在这次调用栈上存在；
 *       JDK HttpClient 的头值必须是 String，这是唯一无法用 char[] 绕开的地方，
 *       因此这里拒绝任何 debug 日志并把日志内容限制为"状态码 + 异常类型"；</li>
 *   <li>不跟随跳转（{@code Redirect.NEVER}）：跳转会把手里的 Key 发给另一个地址；</li>
 *   <li>失败信息不落库、不回传响应体（协议 §9：4xx 的 body 结构未能核实，只有 HTTP 状态可信）。</li>
 * </ol>
 *
 * <p>金额按官方口径是<b>字符串</b>，这里绑定成 {@link BigDecimal}（协议 §8 明确要求，不用 double）。
 */
@Component
public class DeepSeekBalanceGateway {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekBalanceGateway.class);
    private static final String RETRY_LATER = "暂时无法获取账户余额";

    private final ModelAccessProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public DeepSeekBalanceGateway(ModelAccessProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, defaultClient(properties.balance().timeout()));
    }

    /** 供单元测试注入替身客户端；生产装配走上面的构造器。 */
    DeepSeekBalanceGateway(ModelAccessProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    private static HttpClient defaultClient(java.time.Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * @param provider 白名单供应商（base_url 的唯一来源）
     * @param apiKey   明文 Key 字符；本方法不擦除调用方的数组，但绝不把它写进任何返回值或日志
     * @throws BizException BALANCE_UNAVAILABLE 供应商未返回可解析的成功响应
     */
    public BalanceReading fetch(ProviderRef provider, char[] apiKey) {
        if (apiKey == null || apiKey.length == 0) {
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        }
        String bearer = new String(apiKey);
        HttpRequest request = HttpRequest.newBuilder(uri(provider))
                .GET()
                .timeout(properties.balance().timeout())
                .header("Authorization", "Bearer " + bearer)
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("余额查询超时 timeout={}", properties.balance().timeout());
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("余额查询被中断");
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        } catch (IOException e) {
            // 只记类型：JDK HttpClient 的异常文本可能带完整 URL，其中含 Key 的可能位置
            log.warn("余额查询网络失败 type={}", e.getClass().getSimpleName());
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        }
        if (response.statusCode() != 200) {
            // 不回显响应体：结构未核实且可能含内部信息（协议 §9）
            log.warn("余额查询非 200 响应 status={}", response.statusCode());
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        }
        BalanceApiBody body = parse(response.body());
        if (body == null) {
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        }
        return toReading(body, response.statusCode());
    }

    private BalanceReading toReading(BalanceApiBody body, int httpStatus) {
        List<BalanceReading.CurrencyAmount> amounts = new ArrayList<>();
        for (BalanceApiInfo info : safe(body.balanceInfos())) {
            String currency = Vocabulary.canonicalCurrency(info.currency());
            if (currency.isEmpty()) {
                // 没有币种就无法归属，宁可少一条分项也不猜一个币种
                log.warn("余额分项缺少 currency，已跳过");
                continue;
            }
            amounts.add(new BalanceReading.CurrencyAmount(currency,
                    amount(info.totalBalance(), "total_balance"),
                    amount(info.grantedBalance(), "granted_balance"),
                    amount(info.toppedUpBalance(), "topped_up_balance")));
        }
        return new BalanceReading(body.isAvailable(), amounts, httpStatus, null);
    }

    /** 官方金额是字符串；null/空白 → null（"未提供"），解析不了 → 整次抓取失败而不是记 0。 */
    private static BigDecimal amount(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("余额字段格式非法 field={}", field);
            throw new BizException(ErrorCode.BALANCE_UNAVAILABLE, RETRY_LATER);
        }
    }

    private static List<BalanceApiInfo> safe(List<BalanceApiInfo> infos) {
        return infos == null ? List.of() : infos;
    }

    private BalanceApiBody parse(String body) {
        try {
            return objectMapper.readValue(body, BalanceApiBody.class);
        } catch (RuntimeException e) {
            // 不打印响应体内容
            log.warn("余额响应解析失败 type={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private URI uri(ProviderRef provider) {
        String base = provider.baseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + properties.balance().path());
    }

    /**
     * 官方响应结构（协议 §8 示例）。
     *
     * <p>{@code is_available} 用 {@code Boolean} 而不是 boolean：响应里没有该字段时必须保持 null。
     * 未声明的字段被忽略，但本项目的全局 JSON 配置是"未知字段拒绝"——那是对<b>入站</b>请求体的约束，
     * 这里用的是独立的 readValue 调用，仍按宽容处理，避免官方新增字段把余额查询打成失败。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceApiBody(@JsonProperty("is_available") Boolean isAvailable,
                          @JsonProperty("balance_infos") List<BalanceApiInfo> balanceInfos) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceApiInfo(String currency,
                          @JsonProperty("total_balance") String totalBalance,
                          @JsonProperty("granted_balance") String grantedBalance,
                          @JsonProperty("topped_up_balance") String toppedUpBalance) {
    }
}
