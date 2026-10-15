package com.hfj.fitagent.security;

import java.time.ZoneId;

/**
 * 认证主体。业务层的 userId 只能来自这里，不接受请求体或查询参数携带的身份。
 *
 * @param id        user_account.id
 * @param zone      该账户的业务时区，登录时写入会话，避免每次请求回查数据库
 * @param devLogin  本次会话是否由开发登录产生，供接口与前端明确区分真实微信联调与开发登录
 */
public record CurrentUser(long id, ZoneId zone, boolean devLogin) {

    public String externalId() {
        return Long.toString(id);
    }
}
