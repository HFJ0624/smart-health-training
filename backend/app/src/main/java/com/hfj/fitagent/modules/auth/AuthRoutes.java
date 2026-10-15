package com.hfj.fitagent.modules.auth;

/**
 * auth 模块的路由常量。集中一处，避免 SecurityConfig、启动期加固校验与控制器各写一遍字符串而产生漂移。
 */
public final class AuthRoutes {

    public static final String BASE = "/api/auth";
    public static final String WECHAT_LOGIN = BASE + "/wechat-login";
    public static final String DEV_LOGIN = BASE + "/dev-login";
    public static final String LOGOUT = BASE + "/logout";
    public static final String ME = "/api/me";

    private AuthRoutes() {
    }
}
