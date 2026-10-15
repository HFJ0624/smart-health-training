package com.hfj.fitagent.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * JSON 契约层配置。
 *
 * 之所以显式写出来：这些默认值决定了"客户端能不能用多余字段影响服务端行为"，不能依赖库的默认。
 * Boot 4 使用 Jackson 3（tools.jackson），自定义入口是 JsonMapperBuilderCustomizer。
 */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer fitAgentJsonCustomizer() {
        return builder -> builder
                // 未知字段必须失败：接口契约里"未定义字段一律拒绝"依赖这一条，而不是每个 DTO 再写一遍
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // 时间一律 ISO-8601 字符串（Instant 带 Z），不输出 epoch 数字
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
