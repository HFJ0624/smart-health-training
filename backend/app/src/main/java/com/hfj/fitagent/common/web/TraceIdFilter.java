package com.hfj.fitagent.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为每个请求建立 traceId：写入 MDC 供日志与 ApiResponse 共用，并回写响应头便于用户截图排查。
 * OncePerRequestFilter 在 Spring Framework 7.0.9 中存在且未废弃（已 javap 核实）。
 */
@Component
@org.springframework.core.annotation.Order(-400)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TraceIds.HEADER);
        String traceId = (incoming != null && incoming.matches("^[A-Za-z0-9_-]{8,64}$")) ? incoming : TraceIds.newTraceId();
        MDC.put(TraceIds.MDC_KEY, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }
}
