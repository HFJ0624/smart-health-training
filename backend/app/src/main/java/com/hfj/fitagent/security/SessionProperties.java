package com.hfj.fitagent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 服务端会话令牌参数。首版默认 7 天有效、剩余不足 1 天时滑动续期。
 * 这两个时长是工程设置，不是微信或任何外部规范的要求。
 */
@ConfigurationProperties(prefix = "fitagent.auth.session")
public record SessionProperties(Duration ttl, Duration renewWhenBelow, String keyPrefix) {

    public SessionProperties {
        if (ttl == null) {
            ttl = Duration.ofDays(7);
        }
        if (renewWhenBelow == null) {
            renewWhenBelow = Duration.ofDays(1);
        }
        if (keyPrefix == null || keyPrefix.isBlank()) {
            keyPrefix = "fa:sess:";
        }
        if (renewWhenBelow.compareTo(ttl) >= 0) {
            throw new IllegalStateException("fitagent.auth.session.renew-when-below 必须小于 ttl");
        }
    }
}
