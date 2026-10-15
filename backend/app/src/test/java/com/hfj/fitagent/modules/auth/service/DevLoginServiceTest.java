package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.auth.DevLoginProperties;
import com.hfj.fitagent.modules.auth.vo.LoginResultVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开发登录的身份边界：入参只能是配置里枚举出来的测试用户，身份由服务端映射生成。
 *
 * 两个测试用户必须落到两个不同账号，否则"用户间数据隔离"（验收 A5）测不出来。
 */
@ExtendWith(MockitoExtension.class)
class DevLoginServiceTest {

    private static final String DEV_APP_ID = "wx.dev.local";
    private static final String OPEN_ID_A = DevLoginService.syntheticOpenId("dev-a");
    private static final String OPEN_ID_B = DevLoginService.syntheticOpenId("dev-b");

    @Mock
    private WechatLoginService wechatLoginService;

    private DevLoginService service;

    @BeforeEach
    void setUp() {
        service = new DevLoginService(new DevLoginProperties(true, DEV_APP_ID, List.of("dev-a", "dev-b")),
                wechatLoginService);
    }

    @Test
    @DisplayName("未配置的 devUserId 报字段级校验失败，且不进入建号与发令牌流程")
    void unknownDevUserIsRejected() {
        assertThatThrownBy(() -> service.login("dev-c"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(e.payload()).isInstanceOf(List.class);
                    List<?> fields = (List<?>) e.payload();
                    FieldError field = (FieldError) fields.get(0);
                    assertThat(field.field()).isEqualTo("devUserId");
                    assertThat(field.code()).isEqualTo("UNKNOWN_DEV_USER");
                    assertThat(field.rejected()).isEqualTo("dev-c");
                });

        verify(wechatLoginService, never()).openSession(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("空白入参与大小写变体都不被接受：允许列表是精确匹配")
    void blankAndCaseFoldedIdsAreRejected() {
        assertThatThrownBy(() -> service.login("   "))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.login("DEV-A"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.login("someone-elses-openid"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));

        verify(wechatLoginService, never()).openSession(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("两个测试用户映射到两个不同 openid，走同一条 devLogin=true 的开通路径")
    void testUsersMapToDistinctAccounts() {
        when(wechatLoginService.openSession(anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(invocation -> result(true));

        LoginResultVo a = service.login("dev-a");
        LoginResultVo b = service.login("dev-b");

        ArgumentCaptor<String> appIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> openIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> unionIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Boolean> devFlags = ArgumentCaptor.forClass(Boolean.class);
        verify(wechatLoginService, times(2))
                .openSession(appIds.capture(), openIds.capture(), unionIds.capture(), devFlags.capture());

        assertThat(openIds.getAllValues()).containsExactly(OPEN_ID_A, OPEN_ID_B).doesNotHaveDuplicates();
        assertThat(appIds.getAllValues()).containsOnly(DEV_APP_ID);
        assertThat(unionIds.getAllValues()).containsOnlyNulls();
        assertThat(devFlags.getAllValues()).containsOnly(true);
        assertThat(a.devMode()).isTrue();
        assertThat(b.devMode()).isTrue();
    }

    @Test
    @DisplayName("同一 devUserId 的映射稳定，重复登录复用同一账号")
    void mappingIsStableAcrossCalls() {
        when(wechatLoginService.openSession(DEV_APP_ID, OPEN_ID_A, null, true))
                .thenReturn(result(false))
                .thenReturn(result(false));

        assertThat(service.login("dev-a").isNewAccount()).isFalse();
        assertThat(service.login("dev-a").isNewAccount()).isFalse();

        verify(wechatLoginService, times(2)).openSession(DEV_APP_ID, OPEN_ID_A, null, true);
    }

    @Test
    @DisplayName("openid 由 devUserId 派生，客户端无法指定任意 openid")
    void syntheticOpenIdIsDerivedNotProvided() {
        when(wechatLoginService.openSession(anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(invocation -> result(true));

        service.login("dev-b");

        ArgumentCaptor<String> openId = ArgumentCaptor.forClass(String.class);
        verify(wechatLoginService).openSession(anyString(), openId.capture(), any(), anyBoolean());
        assertThat(openId.getValue()).isEqualTo("dev-openid-dev-b");
    }

    private static LoginResultVo result(boolean newAccount) {
        return LoginResultVo.of("9001", "opaque-token", Instant.parse("2026-10-20T07:20:00Z"),
                null, newAccount, true);
    }
}
