package com.hfj.fitagent.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * `POST /api/auth/dev-login` 的请求体（仅 dev）。
 *
 * 客户端只能声明"我要以哪个配置好的测试用户身份登录"，openid 与 appId 由服务端映射生成，
 * 因此不存在"传任意 openid 就变成那个人"的通路（见 docs/p1-api.md §3.2）。
 */
public record DevLoginRequest(
        @NotBlank(message = "开发登录用户标识不能为空")
        @Size(max = 64, message = "开发登录用户标识过长")
        String devUserId) {
}
