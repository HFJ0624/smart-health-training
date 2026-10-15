package com.hfj.fitagent.modules.auth.service;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.auth.WechatProperties;
import com.hfj.fitagent.modules.auth.entity.UserAccountEntity;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.auth.vo.LoginResultVo;
import com.hfj.fitagent.security.SessionTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 登录编排的用例边界：微信侧失败必须发生在"还没碰数据库、还没发令牌"之前；
 * 成功路径只发一次令牌，且 session_key 不允许出现在任何出参里。
 *
 * 这里使用真实的 {@link WechatCode2SessionClient} 加被替换的 JDK HttpClient，
 * 因此 errcode→文案/数字码的映射本身也是被验证的对象，而不只是验证打桩结果。
 */
@ExtendWith(MockitoExtension.class)
class WechatLoginServiceTest {

    private static final String APP_ID = "wxTest-AppId-Not-A-Credential";
    private static final String APP_SECRET = "super-secret-value";
    private static final String SESSION_KEY = "KJ8sTHcREsNEver-Sh0uld-Leak";
    private static final String UNION_ID = "uN-9";
    private static final Instant NOW = Instant.parse("2026-10-13T07:20:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Mock
    private AccountProvisioningService provisioning;
    @Mock
    private SessionTokenService sessionTokens;
    @Mock
    private UserAccountMapper userAccounts;
    @Mock
    private HttpClient httpClient;
    @Mock
    private HttpResponse<String> httpResponse;

    private WechatLoginService service;

    @BeforeEach
    void setUp() {
        service = newService(new WechatProperties(APP_ID, APP_SECRET,
                "https://api.weixin.qq.com/sns/jscode2session", Duration.ofSeconds(5)));
    }

    private WechatLoginService newService(WechatProperties props) {
        WechatCode2SessionClient client = new WechatCode2SessionClient(props, JsonMapper.builder().build(), httpClient);
        return new WechatLoginService(client, props, provisioning, sessionTokens, userAccounts,
                new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("GET 四个参数齐全并逐个编码，无请求体，不跟随跳转")
    void requestCarriesFourEncodedParams() throws Exception {
        givenWechatBody(successBody("oX-Param"));
        givenAccount(21L, "oX-Param", true);
        givenTokenIssued("token-1");

        service.login("a b+c/é");

        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(captor.capture(), any());

        HttpRequest request = captor.getValue();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.bodyPublisher()).isEmpty();
        assertThat(request.timeout()).contains(Duration.ofSeconds(5));
        assertThat(request.uri().getHost()).isEqualTo("api.weixin.qq.com");
        assertThat(request.uri().getRawQuery())
                .contains("appid=" + APP_ID)
                .contains("secret=" + APP_SECRET)
                .contains("js_code=a+b%2Bc%2F%C3%A9")
                .contains("grant_type=authorization_code");
    }

    @ParameterizedTest(name = "errcode={0} → {1}")
    @DisplayName("微信错误码映射为约定文案，并只回传数字码")
    @CsvSource(delimiter = '|', textBlock = """
            -1    | 微信服务繁忙，请稍后重试
            40029 | 登录凭证无效，请重试
            40226 | 该账号登录被拦截
            45011 | 操作过于频繁，请稍后再试
            30001 | 微信登录失败，请稍后重试
            """)
    void wechatErrcodeMappedToStableMessageAndNumericPayload(int errcode, String expectedMessage) throws Exception {
        givenWechatBody("""
                {"errcode":%d,"errmsg":"raw wechat text","session_key":"%s"}
                """.formatted(errcode, SESSION_KEY));

        assertThatThrownBy(() -> service.login("code-1"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED);
                    assertThat(e).hasMessage(expectedMessage);
                    assertThat(e.payload()).isEqualTo(Map.of("wechatErrcode", errcode));
                    assertThat(e.getMessage()).doesNotContain(APP_SECRET, SESSION_KEY);
                });

        assertNothingHappenedAfterWechat();
    }

    @Test
    @DisplayName("errcode=0 但没有 openid：按微信侧失败处理，不建号不发令牌")
    void missingOpenidIsFailure() throws Exception {
        givenWechatBody("{\"errcode\":0,\"session_key\":\"" + SESSION_KEY + "\"}");

        assertThatThrownBy(() -> service.login("code-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED));

        assertNothingHappenedAfterWechat();
    }

    @Test
    @DisplayName("非 200 / 超时 / 网络异常都转成可重试的登录失败，且不外泄凭据")
    void transportFailuresAreRetryableLoginFailures() throws Exception {
        givenHttpStatus(500);
        assertRetryable(() -> service.login("code-1"));

        doThrow(new HttpTimeoutException("read timed out")).when(httpClient).send(any(), any());
        assertRetryable(() -> service.login("code-1"));

        doThrow(new IOException("connection reset")).when(httpClient).send(any(), any());
        assertRetryable(() -> service.login("code-1"));

        assertNothingHappenedAfterWechat();
    }

    @Test
    @DisplayName("中断异常还原中断标记并转成登录失败")
    void interruptKeepsInterruptFlag() throws Exception {
        doThrow(new InterruptedException()).when(httpClient).send(any(), any());

        assertRetryable(() -> service.login("code-1"));

        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    @DisplayName("未配置微信凭据时报配置缺失，且一个字节都不往外发")
    void missingConfigFailsBeforeAnyHttp() {
        WechatProperties unconfigured = new WechatProperties(APP_ID, "  ", null, null);

        assertThatThrownBy(() -> newService(unconfigured).login("code-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WECHAT_CONFIG_MISSING));

        verifyNoInteractions(httpClient, provisioning, sessionTokens, userAccounts);
    }

    @Test
    @DisplayName("成功登录：openid/unionid 原样进入建号流程，只发一次令牌，session_key 不进响应")
    void successIssuesOneTokenAndDropsSessionKey() throws Exception {
        String openId = "oX-MixedCASE-9";
        givenWechatBody(successBody(openId));
        givenAccount(9L, openId, true);
        givenTokenIssued("opaque-token");

        LoginResultVo vo = service.login("code-1");

        assertThat(vo.userId()).isEqualTo("9");
        assertThat(vo.token()).isEqualTo("opaque-token");
        assertThat(vo.tokenType()).isEqualTo("Bearer");
        assertThat(vo.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(vo.isNewAccount()).isTrue();
        assertThat(vo.devMode()).isFalse();
        assertThat(vo.toString()).doesNotContain(SESSION_KEY, openId, UNION_ID, APP_SECRET);

        verify(sessionTokens, times(1)).issue(9L, ZONE, false);
        verify(userAccounts).touchLastLoginAt(9L, NOW);
    }

    @Test
    @DisplayName("重复登录沿用同一账号，isNewAccount=false")
    void repeatLoginReportsExistingAccount() throws Exception {
        givenWechatBody(successBody("oX-9"));
        UserAccountEntity existing = account(9L, "oX-9", "小明");
        when(provisioning.provision(APP_ID, "oX-9", UNION_ID))
                .thenReturn(new AccountProvisioningService.Provisioned(existing, false));
        givenTokenIssued("opaque-token");

        LoginResultVo vo = service.login("code-1");

        assertThat(vo.isNewAccount()).isFalse();
        assertThat(vo.nickname()).isEqualTo("小明");
    }

    @Test
    @DisplayName("账户非 ACTIVE 直接拒绝：不发令牌、不刷新登录时间")
    void disabledAccountIsRejected() throws Exception {
        givenWechatBody(successBody("oX-9"));
        UserAccountEntity disabled = account(9L, "oX-9", null);
        disabled.setStatus("DISABLED");
        when(provisioning.provision(APP_ID, "oX-9", UNION_ID))
                .thenReturn(new AccountProvisioningService.Provisioned(disabled, false));

        assertThatThrownBy(() -> service.login("code-1"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACCOUNT_DISABLED));

        verify(sessionTokens, never()).issue(anyLong(), any(ZoneId.class), anyBoolean());
        verify(userAccounts, never()).touchLastLoginAt(anyLong(), any());
    }

    @Test
    @DisplayName("存量时区非法时按默认时区开通会话，不让脏数据把登录整体打挂")
    void invalidTimezoneFallsBackToDefaultZone() throws Exception {
        givenWechatBody(successBody("oX-9"));
        UserAccountEntity dirty = account(9L, "oX-9", null);
        dirty.setTimezone("Mars/Phobos");
        when(provisioning.provision(APP_ID, "oX-9", UNION_ID))
                .thenReturn(new AccountProvisioningService.Provisioned(dirty, false));
        givenTokenIssued("opaque-token");

        assertThat(service.login("code-1").userId()).isEqualTo("9");

        verify(sessionTokens).issue(9L, ZONE, false);
    }

    private void assertRetryable(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.WECHAT_LOGIN_FAILED);
                    assertThat(e.getMessage()).doesNotContain(APP_SECRET, APP_ID);
                });
    }

    private void assertNothingHappenedAfterWechat() {
        verify(provisioning, never()).provision(any(), any(), any());
        verify(sessionTokens, never()).issue(anyLong(), any(ZoneId.class), anyBoolean());
        verify(userAccounts, never()).touchLastLoginAt(anyLong(), any());
    }

    private void givenWechatBody(String body) throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(body);
    }

    /** 状态码非 200 时客户端在读响应体之前就失败，所以这里只桩状态码，多桩一次 body 会成无用打桩。 */
    private void givenHttpStatus(int status) throws Exception {
        doReturn(httpResponse).when(httpClient).send(any(), any());
        when(httpResponse.statusCode()).thenReturn(status);
    }

    private void givenAccount(long id, String openId, boolean newAccount) {
        when(provisioning.provision(APP_ID, openId, UNION_ID))
                .thenReturn(new AccountProvisioningService.Provisioned(account(id, openId, null), newAccount));
    }

    private void givenTokenIssued(String token) {
        when(sessionTokens.issue(anyLong(), any(ZoneId.class), eq(false)))
                .thenReturn(new SessionTokenService.Issued(token, NOW.plus(Duration.ofDays(7))));
    }

    private static String successBody(String openId) {
        return "{\"openid\":\"" + openId + "\",\"session_key\":\"" + SESSION_KEY + "\",\"unionid\":\"" + UNION_ID + "\"}";
    }

    private static UserAccountEntity account(long id, String openId, String nickname) {
        UserAccountEntity account = new UserAccountEntity();
        account.setId(id);
        account.setWechatAppId(APP_ID);
        account.setWechatOpenId(openId);
        account.setNickname(nickname);
        account.setRole("USER");
        account.setStatus("ACTIVE");
        account.setTimezone("Asia/Shanghai");
        account.setDataRevision(0L);
        return account;
    }
}
