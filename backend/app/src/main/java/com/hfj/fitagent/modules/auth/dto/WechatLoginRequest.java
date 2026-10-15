package com.hfj.fitagent.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * `POST /api/auth/wechat-login` 的请求体。
 *
 * 只有 wx.login 返回的 code 一个字段：身份由后端向微信核实后决定，
 * 请求体里出现 userId/openid/appId 会被全局 FAIL_ON_UNKNOWN_PROPERTIES 拒绝（见 docs/p1-api.md §3.1）。
 * code 由微信生成，长度上限取 128 作为工程护栏（微信实际返回远小于该值），只为挡住异常超长输入。
 */
public record WechatLoginRequest(
        @NotBlank(message = "登录凭证不能为空")
        @Size(max = 128, message = "登录凭证长度超出允许范围")
        String code) {
}
