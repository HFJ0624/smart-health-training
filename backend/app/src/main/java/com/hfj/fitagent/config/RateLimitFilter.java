package com.hfj.fitagent.config;

import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.common.web.TraceIds;
import com.hfj.fitagent.security.AuthenticatedUsers;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;

/**
 * 限流 + 请求体上限。放在 Spring Security 链之前（order 小于 -100），
 * 否则洪水式请求会先做一遍令牌校验才被打回，等于把成本转嫁给自己的依赖。
 *
 * 计数用 Redis INCR + 首次 EXPIRE 的固定窗口，不追求精确滑动窗口：
 * 这里的目的是挡住脚本化刷接口，不是公平调度。Redis 是唯一可用状态源，
 * 因此不建内存回退——多实例下内存计数会各自为政，反而是假的限流。
 */
@Component
@Order(-300)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String PREFIX = "fa:rl:";

    private final StringRedisTemplate redis;
    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(StringRedisTemplate redis, RateLimitProperties properties, ObjectMapper objectMapper) {
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!properties.enabled()) {
            chain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        int bodyLimit = bodyLimitFor(path);
        long declared = request.getContentLengthLong();
        if (declared > bodyLimit) {
            reject(response, ErrorCode.PAYLOAD_TOO_LARGE, null);
            return;
        }

        Bucket bucket = bucketFor(path, request);
        if (bucket != null && !tryAcquire(bucket)) {
            reject(response, ErrorCode.RATE_LIMITED, bucket.window.getSeconds());
            return;
        }
        chain.doFilter(request, response);
    }

    private int bodyLimitFor(String path) {
        return path.startsWith("/api/plans/import") ? properties.maxImportBodyBytes() : properties.maxBodyBytes();
    }

    private record Bucket(String key, int limit, Duration window) {
    }

    private Bucket bucketFor(String path, HttpServletRequest request) {
        if (!path.startsWith("/api/")) {
            return null;
        }
        String identity = callerIdentity(request);
        if (path.startsWith("/api/auth/")) {
            return new Bucket(PREFIX + "login:" + identity, properties.loginPerMinute(), Duration.ofMinutes(1));
        }
        if (path.startsWith("/api/plans/import")) {
            return new Bucket(PREFIX + "import:" + identity, properties.importPerMinute(), Duration.ofMinutes(1));
        }
        return new Bucket(PREFIX + "general:" + identity, properties.generalPerMinute(), Duration.ofMinutes(1));
    }

    /**
     * 已登录按 userId 计数；未登录只能按 remoteAddr。
     * 这里读认证上下文是安全的：本过滤器在安全链之前，此时上下文通常为空，走 remoteAddr 分支。
     */
    private String callerIdentity(HttpServletRequest request) {
        try {
            return "u" + AuthenticatedUsers.requireId();
        } catch (RuntimeException notSignedIn) {
            String remote = request.getRemoteAddr();
            return "ip" + (remote == null ? "unknown" : remote.replace(':', '_').replace('.', '_'));
        }
    }

    private boolean tryAcquire(Bucket bucket) {
        Long count = redis.opsForValue().increment(bucket.key());
        if (count == null) {
            // Redis 异常时不阻断业务：宁可少拦一次，也不因限流组件自身故障把整站打死
            log.warn("限流计数读取异常，本次放行 key={}", bucket.key());
            return true;
        }
        if (count == 1L) {
            redis.expire(bucket.key(), bucket.window());
        }
        return count <= bucket.limit();
    }

    private void reject(HttpServletResponse response, ErrorCode code, Long retryAfterSeconds) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json;charset=UTF-8");
        if (retryAfterSeconds != null && retryAfterSeconds > 0) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        }
        ApiResponse<Object> body = ApiResponse.error(code.code(), code.defaultMessage(), null, TraceIds.current());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
