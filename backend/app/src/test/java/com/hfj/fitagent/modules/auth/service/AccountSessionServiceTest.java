package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.auth.vo.MeVo;
import com.hfj.fitagent.security.CurrentUser;
import com.hfj.fitagent.security.SessionTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 退出与自检：令牌定位只依赖请求头里的明文令牌，身份只依赖 SecurityContext。
 */
@ExtendWith(MockitoExtension.class)
class AccountSessionServiceTest {

    private static final String OPEN_ID = "oX-nEvEr-Sh0w-Up";
    private static final String APP_ID = "wxTest-AppId-Not-A-Credential";

    @Mock
    private SessionTokenService sessionTokens;
    @Mock
    private UserAccountMapper userAccounts;

    private AccountSessionService service;

    @BeforeEach
    void setUp() {
        service = new AccountSessionService(sessionTokens, userAccounts);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("退出撤销请求头里的令牌，scheme 大小写不敏感")
    void logoutRevokesPresentedToken() {
        service.logout("Bearer opaque-token");
        service.logout("bearer  spaced-token ");

        verify(sessionTokens).revoke("opaque-token");
        verify(sessionTokens).revoke("spaced-token");
    }

    @Test
    @DisplayName("无头 / 非 Bearer / 空令牌都正常返回：退出是幂等的")
    void logoutIsIdempotentWithoutUsableToken() {
        service.logout(null);
        service.logout("   ");
        service.logout("Basic dXNlcjpwd2Q=");
        service.logout("Bearer ");

        verify(sessionTokens, never()).revoke(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("GET /api/me 返回账户可见字段，微信身份一项都不外泄")
    void meExposesNoWechatIdentifiers() {
        authenticate(9L, "Asia/Shanghai", false);
        when(userAccounts.selectById(9L)).thenReturn(account());

        MeVo me = service.currentAccount();

        assertThat(me.userId()).isEqualTo("9");
        assertThat(me.role()).isEqualTo("USER");
        assertThat(me.status()).isEqualTo("ACTIVE");
        assertThat(me.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(me.dataRevision()).isEqualTo("7");
        assertThat(me.lastLoginAt()).isEqualTo(Instant.parse("2026-10-13T07:20:00Z"));
        assertThat(me.createdAt()).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
        assertThat(me.devMode()).isFalse();
        assertThat(me.toString()).doesNotContain(OPEN_ID, APP_ID, "uN-union-id");
    }

    @Test
    @DisplayName("开发登录的会话在 /api/me 里如实标注 devMode")
    void meReportsDevMode() {
        authenticate(9L, "Asia/Shanghai", true);
        when(userAccounts.selectById(9L)).thenReturn(account());

        assertThat(service.currentAccount().devMode()).isTrue();
    }

    @Test
    @DisplayName("会话仍在而账号已不存在：按资源不存在处理，不编造档案")
    void meFailsWhenAccountGone() {
        authenticate(404L, "Asia/Shanghai", false);
        when(userAccounts.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.currentAccount())
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    @DisplayName("没有认证上下文时 /api/me 直接被拒，不会退化成匿名读取")
    void meRequiresAuthentication() {
        assertThatThrownBy(() -> service.currentAccount())
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));

        verify(userAccounts, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
    }

    private void authenticate(long id, String zoneId, boolean devLogin) {
        CurrentUser principal = new CurrentUser(id, ZoneId.of(zoneId), devLogin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "opaque-token", List.of()));
    }

    private static UserAccountEntity account() {
        UserAccountEntity entity = new UserAccountEntity();
        entity.setId(9L);
        entity.setWechatAppId(APP_ID);
        entity.setWechatOpenId(OPEN_ID);
        entity.setWechatUnionId("uN-union-id");
        entity.setNickname(null);
        entity.setRole("USER");
        entity.setStatus("ACTIVE");
        entity.setTimezone("Asia/Shanghai");
        entity.setDataRevision(7L);
        entity.setLastLoginAt(Instant.parse("2026-10-13T07:20:00Z"));
        entity.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        return entity;
    }
}
