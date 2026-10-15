package com.hfj.fitagent.config;

import com.hfj.fitagent.modules.auth.AuthRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Arrays;
import java.util.Set;

/**
 * 生产环境加固校验：只要 prod profile 下探测到开发登录路由被注册，立刻让应用启动失败。
 *
 * 这是"prod 不得暴露该入口"的第二道锁。第一道是控制器上的 @Profile("dev") + 配置开关；
 * 加第二道的原因是：一旦有人误改 profile 或误设属性，宁可启动不起来，也不要留下一个可鉴权旁路。
 */
public class ProductionHardeningValidator implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionHardeningValidator.class);
    private static final Set<String> PROD_PROFILES = Set.of("prod", "production");

    private final Environment environment;
    private final RequestMappingHandlerMapping handlerMapping;

    public ProductionHardeningValidator(Environment environment, RequestMappingHandlerMapping handlerMapping) {
        this.environment = environment;
        this.handlerMapping = handlerMapping;
    }

    @Override
    public void afterSingletonsInstantiated() {
        boolean prod = Arrays.stream(environment.getActiveProfiles()).anyMatch(PROD_PROFILES::contains);
        boolean devLoginExposed = handlerMapping.getHandlerMethods().keySet().stream()
                .map(RequestMappingInfo::toString)
                .anyMatch(s -> s.contains(AuthRoutes.DEV_LOGIN));
        if (prod && devLoginExposed) {
            throw new IllegalStateException("prod 环境不允许暴露 " + AuthRoutes.DEV_LOGIN + "，请检查 profile 与 fitagent.auth.dev-login.enabled");
        }
        if (devLoginExposed) {
            log.warn("开发登录已启用（仅允许出现在 dev 环境），所有登录相关测试结论必须标注为开发登录结果");
        }
    }
}
