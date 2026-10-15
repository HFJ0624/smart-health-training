package com.hfj.fitagent.security;

import com.hfj.fitagent.modules.auth.AuthRoutes;
import com.hfj.fitagent.modules.auth.DevLoginProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * 鉴权链路。
 *
 * 关键点：默认要求已认证，公开路径是显式白名单枚举出来的，而不是"先全放行再逐个加注解"。
 * 提示词明确禁止用全局放行替代鉴权。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    ApiResponses apiResponses(ObjectMapper objectMapper) {
        return new ApiResponses(objectMapper);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                           SessionTokenService sessions,
                                           ApiResponses responses,
                                           DevLoginProperties devLoginProperties) throws Exception {
        // 就地构造而不是声明为 Filter Bean：避免被 Servlet 容器再注册一次，导致请求经过两次令牌校验
        BearerTokenAuthenticationFilter bearerFilter = new BearerTokenAuthenticationFilter(sessions, responses);
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.requireExplicitSave(false))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(AuthRoutes.WECHAT_LOGIN).permitAll();
                    // 只有显式开启开发登录才放行该路径；prod 下 enabled 恒为 false（并有启动期二次校验）
                    if (devLoginProperties.enabled()) {
                        auth.requestMatchers(AuthRoutes.DEV_LOGIN).permitAll();
                    }
                    auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(new ApiAuthenticationEntryPoint(responses))
                        .accessDeniedHandler(new ApiAccessDeniedHandler(responses)))
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
