package com.hfj.fitagent.security;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.ZoneId;

/**
 * 业务层取当前用户的唯一途径。
 *
 * Service 方法签名一律接收 long userId 或 CurrentUser，而不是从请求参数拿身份——这样"前端传 userId 决定身份"
 * 在结构上不可能发生，而不是靠每个接口记得做校验。
 */
public final class AuthenticatedUsers {

    private AuthenticatedUsers() {
    }

    public static CurrentUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CurrentUser user)) {
            throw new BizException(ErrorCode.UNAUTHENTICATED);
        }
        return user;
    }

    public static long requireId() {
        return require().id();
    }

    public static ZoneId requireZone() {
        return require().zone();
    }

    public static boolean devLogin() {
        return require().devLogin();
    }
}
