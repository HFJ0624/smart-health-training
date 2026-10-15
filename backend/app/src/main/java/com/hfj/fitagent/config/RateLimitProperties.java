package com.hfj.fitagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流与请求体上限。阈值是工程护栏，不是任何外部规范，因此可配置；
 * 但默认值就是 docs/p1-api.md §1 里写下的那三个数字，改代码不如改配置。
 *
 * 身份口径：已登录用 userId，未登录用 remoteAddr。注意 remoteAddr 在反向代理后会是代理地址，
 * 本项目首版本机直连，因此不解析 X-Forwarded-For——伪造头就能绕过限流，宁可不支持。
 */
@ConfigurationProperties(prefix = "fitagent.rate-limit")
public record RateLimitProperties(boolean enabled, int loginPerMinute, int importPerMinute, int generalPerMinute,
                                 int maxBodyBytes, int maxImportBodyBytes) {

    public RateLimitProperties {
        if (loginPerMinute <= 0) {
            loginPerMinute = 10;
        }
        if (importPerMinute <= 0) {
            importPerMinute = 30;
        }
        if (generalPerMinute <= 0) {
            generalPerMinute = 300;
        }
        if (maxBodyBytes <= 0) {
            maxBodyBytes = 64 * 1024;
        }
        if (maxImportBodyBytes <= 0) {
            maxImportBodyBytes = 256 * 1024;
        }
    }
}
