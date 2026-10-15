package com.hfj.fitagent.modules.auth.vo;

import java.time.Instant;

/**
 * 登录成功响应（微信登录与开发登录同形，见 docs/p1-api.md §3.1/§3.2）。
 *
 * 刻意不包含任何微信标识（openid/unionid/appId）：客户端拿到的是服务端账号 id，
 * 后续所有请求都以该 id 的会话令牌为准，微信身份只留在后端。
 *
 * @param userId      user_account.id；BigInt 一律以字符串出参，避免 JS 精度丢失
 * @param token       不透明会话令牌，仅本次响应返回一次，服务端只存其 SHA-256 摘要
 * @param tokenType   固定 "Bearer"
 * @param expiresAt   会话到期的瞬时值（UTC）
 * @param nickname    账户昵称，未填写时为 null（本轮不在登录时采集昵称）
 * @param isNewAccount 本次登录是否新建了账号；同时决定 HTTP 201/200
 * @param devMode     本次会话是否来自开发登录，供页面显式区分真实微信联调
 */
public record LoginResultVo(String userId,
                            String token,
                            String tokenType,
                            Instant expiresAt,
                            String nickname,
                            boolean isNewAccount,
                            boolean devMode) {

    public static final String TOKEN_TYPE = "Bearer";

    public static LoginResultVo of(String userId, String token, Instant expiresAt, String nickname,
                                   boolean isNewAccount, boolean devMode) {
        return new LoginResultVo(userId, token, TOKEN_TYPE, expiresAt, nickname, isNewAccount, devMode);
    }
}
