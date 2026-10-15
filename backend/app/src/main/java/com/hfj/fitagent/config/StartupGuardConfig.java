package com.hfj.fitagent.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 需要 Web 上下文才能做的启动期检查集中在这里。
 * 声明为 Bean 而不是 @Component：它依赖 RequestMappingHandlerMapping，装配时机比组件扫描更可控。
 */
@Configuration
public class StartupGuardConfig {

    @Bean
    ProductionHardeningValidator productionHardeningValidator(Environment environment,
                                                              // 开 actuator 后容器里有两个 RequestMappingHandlerMapping
                                                              // （MVC 的那个 + controllerEndpointHandlerMapping），
                                                              // 不指名就会 "expected single matching bean but found 2"
                                                              @Qualifier("requestMappingHandlerMapping")
                                                              RequestMappingHandlerMapping handlerMapping) {
        return new ProductionHardeningValidator(environment, handlerMapping);
    }
}
