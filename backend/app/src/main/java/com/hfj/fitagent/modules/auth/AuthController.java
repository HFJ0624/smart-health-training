package com.hfj.fitagent.modules.auth;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.modules.auth.dto.WechatLoginRequest;
import com.hfj.fitagent.modules.auth.service.WechatLoginService;
import com.hfj.fitagent.modules.auth.service.AccountSessionService;
import com.hfj.fitagent.modules.auth.vo.LoginResultVo;
import com.hfj.fitagent.modules.auth.vo.MeVo;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * auth 模块的入站边界：只做参数接收、状态码选择与响应包装，业务规则全在 Service。
 *
 * 路径直接引用 {@link AuthRoutes} 常量，与 SecurityConfig 的白名单共用同一份定义，避免两处字符串漂移。
 * 公开/受保护由 SecurityConfig 判定（docs/p1-api.md §1），这里不再用注解补鉴权。
 */
@RestController
public class AuthController {

    private final WechatLoginService wechatLoginService;
    private final AccountSessionService accountSessions;

    public AuthController(WechatLoginService wechatLoginService, AccountSessionService accountSessions) {
        this.wechatLoginService = wechatLoginService;
        this.accountSessions = accountSessions;
    }

    /** 首次注册返回 201，重复登录返回 200；两者响应体同形，客户端按 isNewAccount 判断即可。 */
    @PostMapping(AuthRoutes.WECHAT_LOGIN)
    public ResponseEntity<ApiResponse<LoginResultVo>> wechatLogin(@Valid @RequestBody WechatLoginRequest request) {
        return loginResponse(wechatLoginService.login(request.code()));
    }

    /** 幂等：退出永远 200，data 为空。原始头需要透传，因为定位会话用的是明文令牌本身。 */
    @PostMapping(AuthRoutes.LOGOUT)
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        accountSessions.logout(authorization);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping(AuthRoutes.ME)
    public ResponseEntity<ApiResponse<MeVo>> me() {
        return ResponseEntity.ok(ApiResponse.ok(accountSessions.currentAccount()));
    }

    static ResponseEntity<ApiResponse<LoginResultVo>> loginResponse(LoginResultVo result) {
        HttpStatus status = result.isNewAccount() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(result));
    }
}
