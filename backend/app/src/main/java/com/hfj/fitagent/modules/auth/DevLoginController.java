package com.hfj.fitagent.modules.auth;

import com.hfj.fitagent.common.web.ApiResponse;
import com.hfj.fitagent.modules.auth.dto.DevLoginRequest;
import com.hfj.fitagent.modules.auth.service.DevLoginService;
import com.hfj.fitagent.modules.auth.vo.LoginResultVo;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * `POST /api/auth/dev-login`：只在没有微信凭据的开发环境存在。
 *
 * 双开关缺一不可——`@Profile("dev")` 决定"非 dev 环境根本不注册这个 Bean"，
 * `@ConditionalOnProperty` 决定"dev 环境也得显式开启"。prod 若探测到该路由，启动期加固校验会让应用起不来
 * （docs/p1-api.md §3.2、docs/p1-scope.md §3.1）。
 *
 * 状态码规则与真实登录完全一致，共用 {@link AuthController#loginResponse}，避免"首登 201/重复 200"两处实现漂移。
 */
@RestController
@Profile("dev")
@ConditionalOnProperty(prefix = "fitagent.auth.dev-login", name = "enabled", havingValue = "true")
public class DevLoginController {

    private final DevLoginService devLoginService;

    DevLoginController(DevLoginService devLoginService) {
        this.devLoginService = devLoginService;
    }

    @PostMapping(AuthRoutes.DEV_LOGIN)
    public ResponseEntity<ApiResponse<LoginResultVo>> devLogin(@Valid @RequestBody DevLoginRequest request) {
        return AuthController.loginResponse(devLoginService.login(request.devUserId()));
    }
}
