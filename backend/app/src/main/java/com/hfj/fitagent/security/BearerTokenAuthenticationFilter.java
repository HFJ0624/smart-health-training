package com.hfj.fitagent.security;

import com.hfj.fitagent.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Bearer 令牌认证过滤器。
 *
 * 三种情形分得很清楚：
 * 1. 完全没有 Authorization 头 —— 不在这里写响应，交给 Spring Security 的 entry point 返回 UNAUTHENTICATED；
 * 2. 带了 Bearer 但摘要查不到（已退出、已过期、伪造）—— 直接写 401 SESSION_EXPIRED，不再进入后续链路；
 * 3. 命中 —— 装配 CurrentUser 并保存上下文。
 *
 * 这里的分支不是"全局放行"：SecurityConfig 里除登录与探针外全部要求已认证。
 */
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final SessionTokenService sessions;
    private final ApiResponses apiResponses;

    public BearerTokenAuthenticationFilter(SessionTokenService sessions, ApiResponses apiResponses) {
        this.sessions = sessions;
        this.apiResponses = apiResponses;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> token = extractToken(request.getHeader("Authorization"));
        if (token.isEmpty()) {
            // 情形 1：可能是公开路径，也可能是未登录访问受保护路径
            chain.doFilter(request, response);
            return;
        }
        Optional<SessionTokenService.Session> session = sessions.verify(token.get());
        if (session.isEmpty()) {
            // 情形 2：令牌形态存在但服务端不认，明确告诉客户端需要重新登录
            apiResponses.write(response, ErrorCode.SESSION_EXPIRED, ErrorCode.SESSION_EXPIRED.defaultMessage(), null);
            return;
        }
        CurrentUser user = new CurrentUser(session.get().userId(), session.get().zone(), session.get().devLogin());
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(user, token.get(), List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    private static Optional<String> extractToken(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
            return Optional.empty();
        }
        String token = value.substring(PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
