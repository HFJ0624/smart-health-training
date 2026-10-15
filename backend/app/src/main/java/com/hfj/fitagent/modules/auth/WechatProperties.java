package com.hfj.fitagent.modules.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 微信登录服务端参数。AppSecret 只能来自环境变量或受保护的本机配置，不入库、不入 Git、不打日志。
 *
 * code2Session 是 GET 且四个 query 参数均必填、无请求体；地址固定为官方域名，不接受运行期改写，
 * 避免任何"由输入决定外呼地址"的空间。
 */
@ConfigurationProperties(prefix = "fitagent.wechat")
public record WechatProperties(String appId, String appSecret, String code2SessionUrl, Duration timeout) {

    public static final String DEFAULT_URL = "https://api.weixin.qq.com/sns/jscode2session";

    public WechatProperties {
        if (code2SessionUrl == null || code2SessionUrl.isBlank()) {
            code2SessionUrl = DEFAULT_URL;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(5);
        }
    }

    /** 未配置凭据时登录接口返回可识别错误，而不是拿 null 去拼 URL。 */
    public boolean configured() {
        return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    /** 只用于日志：证明配置存在，但不泄露内容。 */
    public String safeDescribe() {
        return "appId=" + (appId == null || appId.isBlank() ? "<未配置>" : appId)
                + ", secret=" + (appSecret == null || appSecret.isBlank() ? "<未配置>" : "<已配置>")
                + ", url=" + code2SessionUrl + ", timeout=" + timeout;
    }
}
