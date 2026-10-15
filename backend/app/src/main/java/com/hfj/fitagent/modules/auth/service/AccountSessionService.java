package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.auth.vo.MeVo;
import com.hfj.fitagent.security.AuthenticatedUsers;
import com.hfj.fitagent.security.CurrentUser;
import com.hfj.fitagent.security.SessionTokenService;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 当前会话与账户自检：退出与 `GET /api/me`。
 *
 * 身份只从 SecurityContext 取（docs/p1-architecture.md §5）：这两个操作都不接收 userId 参数，
 * 所以"前端传 userId 就查别人"在方法签名层面不可能成立。
 */
@Service
public class AccountSessionService {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionTokenService sessionTokens;
    private final UserAccountMapper userAccounts;

    public AccountSessionService(SessionTokenService sessionTokens, UserAccountMapper userAccounts) {
        this.sessionTokens = sessionTokens;
        this.userAccounts = userAccounts;
    }

    /**
     * 退出：删除服务端会话，令牌立即失效（验收 A4）。
     *
     * 幂等——令牌缺失、格式不符或早已被删，都同样正常返回，不抛错：
     * 客户端无论如何都要清本地令牌并回到登录页，后端没必要把"已经登出"报成失败。
     *
     * @param authorizationHeader 原始 Authorization 头；只有这里能从明文令牌定位会话，
     *                            认证上下文里的主体不含明文令牌
     */
    public void logout(String authorizationHeader) {
        bearerToken(authorizationHeader).ifPresent(sessionTokens::revoke);
    }

    public MeVo currentAccount() {
        CurrentUser principal = AuthenticatedUsers.require();
        UserAccountEntity account = userAccounts.selectById(principal.id());
        if (account == null) {
            // 会话还在而账号已不存在（例如后续做注销清理）：与"不是你的资源"同形，不区分
            throw BizException.notFound();
        }
        return new MeVo(Long.toString(account.getId()), account.getNickname(), account.getRole(),
                account.getStatus(), account.getTimezone(),
                account.getDataRevision() == null ? null : Long.toString(account.getDataRevision()),
                account.getLastLoginAt(), account.getCreatedAt(), principal.devLogin());
    }

    private static Optional<String> bearerToken(String header) {
        if (header == null || header.isBlank()) {
            return Optional.empty();
        }
        String value = header.trim();
        // 与认证过滤器同口径：scheme 大小写不敏感，令牌本身只 trim 不改写
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return Optional.empty();
        }
        String token = Ids.exactExternalId(value.substring(BEARER_PREFIX.length()));
        return token == null ? Optional.empty() : Optional.of(token);
    }
}
