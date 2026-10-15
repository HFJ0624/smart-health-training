package com.hfj.fitagent.security;

import com.hfj.fitagent.common.error.ErrorCode;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 未认证与无权访问都用统一响应包写出，保持与业务接口同一形状，前端只需看 code。
 * 越权访问他人资源不会走到这里：Service 层把它转成 RESOURCE_NOT_FOUND（404），避免泄露资源是否存在。
 */
class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ApiResponses responses;

    ApiAuthenticationEntryPoint(ApiResponses responses) {
        this.responses = responses;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failed)
            throws IOException, ServletException {
        responses.write(response, ErrorCode.UNAUTHENTICATED, ErrorCode.UNAUTHENTICATED.defaultMessage(), null);
    }
}

class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final ApiResponses responses;

    ApiAccessDeniedHandler(ApiResponses responses) {
        this.responses = responses;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException failed)
            throws IOException, ServletException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        responses.write(response, ErrorCode.ACCOUNT_DISABLED, ErrorCode.ACCOUNT_DISABLED.defaultMessage(), null);
    }
}
