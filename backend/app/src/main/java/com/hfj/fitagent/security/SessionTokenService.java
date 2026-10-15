package com.hfj.fitagent.security;

import com.hfj.fitagent.common.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 服务端不透明会话令牌。
 *
 * 设计要点（对应提示词"成熟鉴权组件 + 服务端随机会话令牌 + Redis 保存令牌摘要"）：
 * - 令牌是 32 字节 SecureRandom 的 base64url，不含任何业务信息，也不是 JWT：不自造签名或加密算法。
 * - Redis 的键是令牌的 SHA-256 摘要：即使 Redis 被读，也拿不到可用于认证的明文令牌。
 * - 数据库不存令牌；日志不打印令牌（见 logback 屏蔽与"只记 userId"约定）。
 * - 退出即 DEL，使该会话在服务端立即失效，不依赖客户端配合。
 */
@Service
public class SessionTokenService {

    private static final Logger log = LoggerFactory.getLogger(SessionTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final String DELIMITER = "|";

    private final StringRedisTemplate redis;
    private final SessionProperties properties;
    private final AppClock clock;

    public SessionTokenService(StringRedisTemplate redis, SessionProperties properties, AppClock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 会话记录。devLogin 随会话存留，用于把"开发登录"与"真实微信联调"的接口行为区分开。
     */
    public record Session(long userId, ZoneId zone, boolean devLogin, Instant expiresAt) {
    }

    public record Issued(String token, Instant expiresAt) {
    }

    public Issued issue(long userId, ZoneId zone, boolean devLogin) {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        Instant expiresAt = clock.now().plus(properties.ttl());
        redis.opsForValue().set(key(token), serialize(userId, zone, devLogin, expiresAt), properties.ttl());
        return new Issued(token, expiresAt);
    }

    /**
     * 校验令牌。命中且剩余有效期不足 renewWhenBelow 时滑动续期。
     * 任何解析异常都按"会话无效"处理（fail-closed），不做默认时区兜底。
     */
    public Optional<Session> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String redisKey = key(token);
        String value = redis.opsForValue().get(redisKey);
        if (value == null) {
            return Optional.empty();
        }
        Parsed parsed;
        try {
            parsed = deserialize(value);
        } catch (RuntimeException e) {
            log.error("会话记录格式异常，按失效处理", e);
            redis.delete(redisKey);
            return Optional.empty();
        }
        Long remainingSeconds = redis.getExpire(redisKey);
        if (remainingSeconds == null || remainingSeconds <= 0) {
            // 读取到值但没有有效 TTL 属于异常状态（写入时总带 TTL），按失效处理并清理
            log.error("会话缺少有效 TTL，按失效处理 userId={}", parsed.userId());
            redis.delete(redisKey);
            return Optional.empty();
        }
        Duration remaining = Duration.ofSeconds(remainingSeconds);
        if (remaining.compareTo(properties.renewWhenBelow()) < 0) {
            Instant newExpiry = clock.now().plus(properties.ttl());
            redis.opsForValue().set(redisKey, serialize(parsed.userId(), parsed.zone(), parsed.devLogin(), newExpiry), properties.ttl());
            return Optional.of(new Session(parsed.userId(), parsed.zone(), parsed.devLogin(), newExpiry));
        }
        return Optional.of(new Session(parsed.userId(), parsed.zone(), parsed.devLogin(), parsed.expiresAt()));
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redis.delete(key(token));
        }
    }

    private String key(String token) {
        return properties.keyPrefix() + sha256Hex(token);
    }

    private static String serialize(long userId, ZoneId zone, boolean devLogin, Instant expiresAt) {
        return userId + DELIMITER + zone.getId() + DELIMITER + (devLogin ? "1" : "0") + DELIMITER + expiresAt.getEpochSecond();
    }

    private record Parsed(long userId, ZoneId zone, boolean devLogin, Instant expiresAt) {
    }

    private static Parsed deserialize(String value) {
        String[] parts = value.split("\\" + DELIMITER);
        if (parts.length != 4) {
            throw new IllegalArgumentException("会话记录字段数不符: " + parts.length);
        }
        return new Parsed(Long.parseLong(parts[0]), ZoneId.of(parts[1]), "1".equals(parts[2]), Instant.ofEpochSecond(Long.parseLong(parts[3])));
    }

    /** 供其它组件复用同一摘要口径。 */
    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }
}
