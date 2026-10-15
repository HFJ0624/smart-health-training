package com.hfj.fitagent.agent.fake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

/**
 * Fake 模型的启动期检查：prod profile 下一旦启用就直接让应用起不来。
 *
 * <p>与 {@code config/ProductionHardeningValidator}（dev-login 的第二道锁）同一手法，
 * 但那个类检查的是"处理器映射里出现了开发路由"，需要 Web 上下文；这里只需要 Environment，
 * 所以声明为普通 {@code @Component} 即可，<b>不</b>去改 config 包（模块边界见 docs/agent-v1-design.md §2）。
 *
 * <p>为什么要三道锁：{@code @Profile} 会被误改、开关会被环境变量覆盖、装配顺序会变——
 * 任一处失误的结果都是"生产环境用假模型回答了用户的问题，还记了账"，这比启动失败严重得多。
 */
@Component
public class FakeModelStartupGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(FakeModelStartupGuard.class);
    private static final Set<String> PROD_PROFILES = Set.of("prod", "production");
    private static final String ENABLED_PROPERTY = "fitagent.agent.fake-model.enabled";

    private final Environment environment;
    private final boolean fakeEnabled;

    public FakeModelStartupGuard(Environment environment, FakeModelProperties properties) {
        this.environment = environment;
        this.fakeEnabled = properties != null && properties.enabled();
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (isProduction() && fakeEnabled) {
            throw FakeErrorCode.productionForbidden();
        }
        if (fakeEnabled) {
            log.warn("Fake 模型模式已启用（profiles={}）：本轮所有模型结果都来自本地脚本，"
                    + "不得作为真实供应商联调或费用记账的证据", Arrays.toString(environment.getActiveProfiles()));
        }
    }

    /** 与 config/ProductionHardeningValidator 同口径：prod 与 production 都算生产。 */
    public boolean isProduction() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(PROD_PROFILES::contains);
    }
}
