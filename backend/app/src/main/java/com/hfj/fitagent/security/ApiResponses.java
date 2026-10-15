package com.hfj.fitagent.security;

import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把统一响应包写给 servlet 层。过滤器与 Spring Security 的 entry point 都要直接写响应，
 * 因为它们不在 DispatcherServlet 的异常处理链路内。
 */
final class ApiResponses {

    private final ObjectMapper mapper;

    ApiResponses(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void write(HttpServletResponse response, ErrorCode code, String message, Object payload) throws IOException {
        int status = code.status().value();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Object> body = ApiResponse.error(code.code(), message, payload, com.hfj.fitagent.common.web.TraceIds.current());
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
