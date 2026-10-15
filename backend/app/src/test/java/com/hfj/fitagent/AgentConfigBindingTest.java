package com.hfj.fitagent;

import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.fake.FakeModelProperties;
import com.hfj.fitagent.agent.model.deepseek.DeepSeekProperties;
import com.hfj.fitagent.modules.coach.CoachProperties;
import com.hfj.fitagent.modules.modelaccess.ModelAccessProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实的 {@code application.yml} 能不能绑成那些配置 record（docs/agent-v1-design.md §3、§5、§10）。
 *
 * <p>为什么单独测这个：{@code @ConfigurationProperties} 对**写错的键名是静默忽略**的——
 * README 里承诺"闸门数值改配置即可"，而 yml 里少写一个横杠或拼错一段前缀时，
 * 应用照样启动，只是永远在用代码默认值。那种错误只能靠"把 yml 真读一遍再绑一次"来发现。
 */
class AgentConfigBindingTest {

    private static Binder binder;

    @BeforeAll
    static void loadYaml() throws IOException {
        Path file = Path.of("src/main/resources/application.yml");
        assertThat(file).as("测试必须读到真实配置文件，而不是复制一份").exists();
        ByteArrayResource resource = new ByteArrayResource(Files.readAllBytes(file));
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", resource);
        StandardEnvironment environment = new StandardEnvironment();
        sources.forEach(source -> environment.getPropertySources().addFirst(source));
        // 占位符解析必须走整个 Environment：${FITAGENT_MASTER_KEY:} 这类默认值只在 Environment 里才解得开
        binder = new Binder(ConfigurationPropertySources.from(sources),
                new org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver(
                        environment));
    }

    @Test
    @DisplayName("执行循环的闸门来自配置：4 次模型调用 / 8 次工具 / 单轮 4 / 120 秒截止")
    void runLimitsBindFromYaml() {
        RunLimits limits = binder.bind("fitagent.agent.limits", RunLimits.class).orElseGet(() -> { throw new IllegalStateException("配置绑定失败：yml 里没有这一段或键名写错"); });

        assertThat(limits.maxModelCalls()).isEqualTo(4);
        assertThat(limits.maxToolCalls()).isEqualTo(8);
        assertThat(limits.maxToolsPerRound()).isEqualTo(4);
        assertThat(limits.maxResultBytes()).isEqualTo(8192);
        assertThat(limits.maxOutputTokens()).isEqualTo(1024);
        assertThat(limits.deadlineSeconds()).isEqualTo(120L);
    }

    @Test
    @DisplayName("DeepSeek 接入参数：默认关闭思考模式，读取超时 30 秒，路径不带 /v1")
    void deepSeekPropertiesBindFromYaml() {
        DeepSeekProperties properties = binder.bind("fitagent.agent.model.deepseek",
                DeepSeekProperties.class).orElseGet(() -> { throw new IllegalStateException("配置绑定失败：yml 里没有这一段或键名写错"); });

        assertThat(properties.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.chatCompletionsPath()).isEqualTo("/chat/completions");
        assertThat(properties.thinking()).isEqualTo("disabled");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.chatCompletionsUri())
                .isEqualTo("https://api.deepseek.com/chat/completions");
    }

    @Test
    @DisplayName("Fake 模型默认关闭；密钥配置项存在且留空表示未配置（不是默认密钥）")
    void fakeModelAndKeysBindFromYaml() {
        FakeModelProperties fake = binder.bind("fitagent.agent.fake-model", FakeModelProperties.class)
                .orElseGet(() -> { throw new IllegalStateException("配置绑定失败：yml 里没有这一段或键名写错"); });
        assertThat(fake.enabled()).isFalse();

        ModelAccessProperties keys = binder.bind("fitagent.modelaccess", ModelAccessProperties.class)
                .orElseGet(() -> { throw new IllegalStateException("配置绑定失败：yml 里没有这一段或键名写错"); });
        assertThat(keys.keyVersion()).isEqualTo("v1");
        assertThat(keys.masterKeyConfigured()).isFalse();
        assertThat(keys.fingerprintKeyConfigured()).isFalse();
        assertThat(keys.balance().path()).isEqualTo("/user/balance");
        assertThat(keys.balance().staleAfter()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("coach 与 Worker 配置齐备：心跳必须小于租约，否则配置类直接拒绝")
    void coachAndWorkerBindFromYaml() {
        CoachProperties coach = binder.bind("fitagent.coach", CoachProperties.class).orElseGet(() -> { throw new IllegalStateException("配置绑定失败：yml 里没有这一段或键名写错"); });

        assertThat(coach.providerCode()).isEqualTo("DEEPSEEK");
        assertThat(coach.maxQuestionChars()).isEqualTo(2000);
        assertThat(coach.historyTurns()).isEqualTo(6);
        assertThat(coach.worker().coreSize()).isEqualTo(1);
        assertThat(coach.worker().maxSize()).isEqualTo(2);
        assertThat(coach.worker().queueCapacity()).isEqualTo(32);
        assertThat(coach.worker().leaseSeconds()).isEqualTo(60);
        assertThat(coach.worker().leaseHeartbeatSeconds()).isEqualTo(20);
        assertThat(coach.worker().scanIntervalSeconds()).isEqualTo(60);
        assertThat(coach.worker().scanBatchSize()).isEqualTo(20);
    }
}
