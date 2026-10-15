package com.hfj.fitagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * FitAgent 当前唯一后端应用。业务按 modules 分包，边界与调用方向见 docs/p1-architecture.md §4。
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.hfj.fitagent")
public class FitAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FitAgentApplication.class, args);
    }
}
