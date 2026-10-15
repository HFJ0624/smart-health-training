package com.hfj.fitagent.modules.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 开发登录配置。仅 dev 环境且显式开启时存在。
 *
 * 两个独立测试用户是硬性要求（用于验证用户间数据隔离），数量不符直接启动失败，
 * 避免出现"开了 dev-login 但只有一个用户"这种隔离测试跑不起来的状态。
 */
@ConfigurationProperties(prefix = "fitagent.auth.dev-login")
public record DevLoginProperties(boolean enabled, String appId, List<String> testUserIds) {

    public static final int REQUIRED_TEST_USERS = 2;

    public DevLoginProperties {
        testUserIds = testUserIds == null ? List.of() : List.copyOf(testUserIds);
        if (enabled) {
            if (appId == null || appId.isBlank()) {
                throw new IllegalStateException("开启 dev-login 必须提供 fitagent.auth.dev-login.app-id");
            }
            if (testUserIds.size() != REQUIRED_TEST_USERS) {
                throw new IllegalStateException("dev-login 必须恰好配置 " + REQUIRED_TEST_USERS + " 个测试用户，当前 " + testUserIds.size());
            }
            if (testUserIds.stream().distinct().count() != REQUIRED_TEST_USERS) {
                throw new IllegalStateException("dev-login 的两个测试用户标识不能相同");
            }
        }
    }
}
