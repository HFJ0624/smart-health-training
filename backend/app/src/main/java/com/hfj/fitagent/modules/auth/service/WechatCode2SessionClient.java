package com.hfj.fitagent.modules.auth.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.modules.auth.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * 微信 `jscode2session` 出口。
 *
 * 三条不可逾越的边界（docs/p1-api.md §3.1、docs/p1-architecture.md §5）：
 * 1. AppSecret 只从后端配置进入 URL，绝不写日志、绝不进响应、绝不出现在异常文案里；
 * 2. 微信原始响应不回传客户端，只回传数字 errcode，业务失败一律转成 {@link ErrorCode#WECHAT_LOGIN_FAILED}；
 * 3. `session_key` 解析阶段就不落地——解析结构里根本没有该字段，因此无法被存储、返回或打印。
 */
@Component
public class WechatCode2SessionClient {

    private static final Logger log = LoggerFactory.getLogger(WechatCode2SessionClient.class);
    private static final String GRANT_TYPE = "authorization_code";
    private static final String RETRY_LATER = "微信服务繁忙，请稍后重试";

    private final WechatProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public WechatCode2SessionClient(WechatProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, defaultClient(properties.timeout()));
    }

    /** 供单元测试注入替身客户端：生产装配走上面的构造器。 */
    WechatCode2SessionClient(WechatProperties properties, ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    private static HttpClient defaultClient(Duration timeout) {
        return HttpClient.newBuilder()
                .connectTimeout(timeout)
                // 外呼带 AppSecret，禁止跟随跳转把凭据发往任何其它地址
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 微信侧核实结果：只保留身份所需的两个字段。
     *
     * @param openId 必填，账号唯一标识来源
     * @param unionId 可选，未绑开放平台时为 null
     */
    public record WechatIdentity(String openId, String unionId) {
    }

    /**
     * 用 code 换取 openid/unionid。
     *
     * @throws BizException WECHAT_CONFIG_MISSING 后端未配置凭据；WECHAT_LOGIN_FAILED 微信侧或网络失败
     */
    public WechatIdentity resolve(String code) {
        if (!properties.configured()) {
            throw new BizException(ErrorCode.WECHAT_CONFIG_MISSING);
        }
        String jsCode = Ids.exactExternalId(code);
        if (jsCode == null) {
            throw failed(null, "登录凭证无效，请重试");
        }
        WechatSessionResponse response = request(jsCode);
        Integer errcode = response.errcode();
        if (errcode != null && errcode != 0) {
            // 只回传数字码：微信的 errmsg 可能含内部信息，不进客户端
            throw failed(errcode, messageFor(errcode));
        }
        String openId = Ids.exactExternalId(response.openid());
        if (openId == null) {
            log.warn("jscode2session 成功响应缺少 openid");
            throw failed(errcode, RETRY_LATER);
        }
        return new WechatIdentity(openId, Ids.exactExternalId(response.unionid()));
    }

    private WechatSessionResponse request(String jsCode) {
        HttpRequest request = HttpRequest.newBuilder(sessionUri(jsCode))
                .GET()
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            log.warn("jscode2session 请求超时 timeout={}", properties.timeout());
            throw failed(null, RETRY_LATER);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("jscode2session 请求被中断");
            throw failed(null, RETRY_LATER);
        } catch (IOException e) {
            // 只记异常类型：JDK HttpClient 的异常文本可能带上完整请求地址，其中含 AppSecret
            log.warn("jscode2session 网络失败 type={}", e.getClass().getSimpleName());
            throw failed(null, RETRY_LATER);
        }

        if (response.statusCode() != 200) {
            log.warn("jscode2session 非 200 响应 status={}", response.statusCode());
            throw failed(null, RETRY_LATER);
        }
        try {
            return objectMapper.readValue(response.body(), WechatSessionResponse.class);
        } catch (RuntimeException e) {
            // 不打印响应体：其中含 session_key
            log.warn("jscode2session 响应解析失败 type={}", e.getClass().getSimpleName());
            throw failed(null, "微信登录失败，请稍后重试");
        }
    }

    /** 四个参数均必填，GET 无请求体；每个值单独编码，避免 code 中的特殊字符改变查询结构。 */
    private URI sessionUri(String jsCode) {
        String query = String.join("&",
                "appid=" + encode(properties.appId()),
                "secret=" + encode(properties.appSecret()),
                "js_code=" + encode(jsCode),
                "grant_type=" + encode(GRANT_TYPE));
        return URI.create(properties.code2SessionUrl() + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 文案映射与 docs/p1-api.md §3.1 一致；未列出的码只回传数字，不猜测语义。 */
    static String messageFor(int errcode) {
        return switch (errcode) {
            case -1 -> RETRY_LATER;
            case 40029 -> "登录凭证无效，请重试";
            case 40226 -> "该账号登录被拦截";
            case 45011 -> "操作过于频繁，请稍后再试";
            default -> "微信登录失败，请稍后重试";
        };
    }

    private static BizException failed(Integer errcode, String message) {
        Object payload = errcode == null ? null : Map.of("wechatErrcode", errcode);
        return new BizException(ErrorCode.WECHAT_LOGIN_FAILED, message, payload);
    }

    /**
     * 微信响应的解析结构，仅在本类内部使用，绝不作为接口响应返回。
     *
     * 注意：这里<b>没有</b> session_key 字段，也没有 errmsg——前者本轮无任何消费方（服务端不解密微信数据），
     * 存下来只是多一处泄露面；后者属微信内部文案，不回传客户端。未声明的字段被忽略，
     * 因此微信后续新增字段也不会把正常登录打成失败。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WechatSessionResponse(String openid,
                                 String unionid,
                                 Integer errcode) {
    }
}
