package com.hfj.fitagent;

import com.hfj.fitagent.agent.fake.FakeModelProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fake 模型开关到底能不能被环境变量打开（2026-10-16 实测钉死，别再靠猜）。
 *
 * <p>背景：交接报告一度断言"`application.yml` 写死 false 且环境变量名映射不上，所以开关是死的"。
 * 真机复测推翻了它——设 `FITAGENT_AGENT_FAKE_MODEL_ENABLED=true` 启动后
 * `FakeModelClient` 与 `FakeModelStartupGuard` 都正常装配并打了告警。
 * 原因有两条，都是 Spring Boot 的既定行为：环境变量优先级**高于**配置文件，
 * 且 `SystemEnvironmentPropertySource` 会把属性名里的 `-` 也映射成 `_`。
 * 这三条断言就是把这两点钉住：谁改键名、谁加 `@PropertySource` 顺序、谁把 yml 改成
 * `@Value` 硬编码，都会在这里失败，而不是等到演示当天才发现。
 */
class FakeModelSwitchBindingTest {

    private static final String PROPERTY = "fitagent.agent.fake-model.enabled";

    private static StandardEnvironment envWith(Map<String, Object> simulatedSystemEnvironment) throws IOException {
        Path file = Path.of("src/main/resources/application.yml");
        assertThat(file).as("必须读真实的 application.yml，不能复制一份到测试资源里").exists();
        ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(file));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("application.yml", resource);

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove("systemEnvironment");
        env.getPropertySources().remove("systemProperties");
        // 模拟环境变量：放在最前，复现"OS 环境变量优先于配置文件"的真实顺序
        env.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("simulatedSystemEnvironment",
                        new LinkedHashMap<>(simulatedSystemEnvironment)));
        yaml.forEach(source -> env.getPropertySources().addLast(source));
        return env;
    }

    @Test
    @DisplayName("没有环境变量时，yml 的默认值就是关闭")
    void defaultIsDisabled() throws IOException {
        Binder binder = Binder.get(envWith(Map.of()));
        assertThat(binder.bind(PROPERTY, Boolean.class).orElse(true)).isFalse();
        assertThat(binder.bind("fitagent.agent.fake-model", FakeModelProperties.class).get().enabled()).isFalse();
    }

    @Test
    @DisplayName("文档与 .env.example 用的名字确实能覆盖 yml 的 false")
    void documentedEnvNameOverridesYaml() throws IOException {
        Binder binder = Binder.get(envWith(Map.of("FITAGENT_AGENT_FAKE_MODEL_ENABLED", "true")));
        assertThat(binder.bind(PROPERTY, Boolean.class).orElse(false)).isTrue();
        FakeModelProperties props = binder.bind("fitagent.agent.fake-model", FakeModelProperties.class).get();
        assertThat(props.enabled()).isTrue();
    }

    @Test
    @DisplayName("文档与 .env.example 用的两种写法（下划线式、保留连字符式）都能命中")
    void documentedSpellingsBind() throws IOException {
        // 2026-10-16 实测：这两种都能打开开关；把连字符整个删掉的那种（FITAGENT_AGENT_FAKEMODEL_ENABLED）
        // 命中不稳定，所以不写进断言、也不要往 .env 里改成它。
        for (String name : List.of("FITAGENT_AGENT_FAKE_MODEL_ENABLED", "FITAGENT_AGENT_FAKE-MODEL_ENABLED")) {
            Binder binder = Binder.get(envWith(Map.of(name, "true")));
            assertThat(binder.bind(PROPERTY, Boolean.class).orElse(false))
                    .as("环境变量 %s 应该能打开 Fake 开关", name)
                    .isTrue();
        }
    }
}
