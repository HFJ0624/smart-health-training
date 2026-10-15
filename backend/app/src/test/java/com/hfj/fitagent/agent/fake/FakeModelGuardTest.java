package com.hfj.fitagent.agent.fake;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelClient;
import com.hfj.fitagent.agent.model.ModelClientLocator;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ThinkingMode;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fake 模式的三把锁（docs/agent-v1-design.md §13、§14）。
 *
 * <p>要证明的结论是："生产构建静默连到假模型而不是 DeepSeek"不可能发生 ——
 * 装配条件（profile + 开关）、启动期守卫、运行期解析三处都会拒绝，而不只是其中一处。
 */
class FakeModelGuardTest {

    private static ModelResponse text(String content) {
        return new ModelResponse(content, List.of(), FinishReason.STOP, null, null, false);
    }

    @Test
    @DisplayName("prod + 开关为 true → 启动失败")
    void productionWithFakeEnabledFailsStartup() {
        StandardEnvironment prod = AgentTestSupport.environment("prod");
        FakeModelStartupGuard guard = new FakeModelStartupGuard(prod, new FakeModelProperties(true, null, null));

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOfSatisfying(IllegalStateException.class, e -> assertThat(e.getMessage())
                        .contains("prod").contains("fitagent.agent.fake-model.enabled"));
    }

    @Test
    @DisplayName("production 别名同样算生产环境")
    void productionAliasAlsoFails() {
        FakeModelStartupGuard guard = new FakeModelStartupGuard(AgentTestSupport.environment("production"),
                new FakeModelProperties(true, null, null));

        assertThatThrownBy(guard::afterSingletonsInstantiated).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("dev + 开关为 true → 允许（但结果不能当成供应商联调证据）")
    void devWithFakeEnabledStarts() {
        FakeModelStartupGuard guard = new FakeModelStartupGuard(AgentTestSupport.environment("dev"),
                new FakeModelProperties(true, null, null));

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod 且开关为 false → 不干扰正常启动")
    void productionWithoutFakeIsFine() {
        FakeModelStartupGuard guard = new FakeModelStartupGuard(AgentTestSupport.environment("prod"),
                FakeModelProperties.disabled());

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("运行期第二道锁：prod 下解析供应商时拒绝返回合成实现")
    void locatorRefusesSyntheticClientInProduction() {
        FakeModelClient fake = new FakeModelClient(new FakeModelProperties(true, "DEEPSEEK", null));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("fitagent.agent.fake-model.enabled", "true");

        ModelClientLocator locator = new ModelClientLocator(List.<ModelClient>of(fake), environment);

        assertThatThrownBy(() -> locator.forProvider("DEEPSEEK"))
                .isInstanceOfSatisfying(ModelProviderException.class, e ->
                        assertThat(e.providerErrorCode()).isEqualTo(FakeErrorCode.SYNTHETIC_REJECTED));
    }

    @Test
    @DisplayName("prod 下真实实现优先，且 fake 不会被选中")
    void locatorPicksRealClientInProduction() {
        RecordingRealClient real = new RecordingRealClient();
        FakeModelClient fake = new FakeModelClient(new FakeModelProperties(true, "DEEPSEEK", null));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("fitagent.agent.fake-model.enabled", "true");

        ModelClient chosen = new ModelClientLocator(List.of(real, fake), environment).forProvider("DEEPSEEK");

        assertThat(chosen).isSameAs(real);
        assertThat(chosen.synthetic()).isFalse();
    }

    @Test
    @DisplayName("dev 且显式开启时才会用合成实现（本地无 Key 也能验证协议闭环）")
    void devExplicitSwitchSelectsSynthetic() {
        RecordingRealClient real = new RecordingRealClient();
        FakeModelClient fake = new FakeModelClient(new FakeModelProperties(true, "DEEPSEEK", null));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("fitagent.agent.fake-model.enabled", "true");

        assertThat(new ModelClientLocator(List.of(real, fake), environment).forProvider("DEEPSEEK")).isSameAs(fake);

        environment.setProperty("fitagent.agent.fake-model.enabled", "false");
        assertThat(new ModelClientLocator(List.of(real, fake), environment).forProvider("DEEPSEEK")).isSameAs(real);
    }

    @Test
    @DisplayName("同一 provider 装配两个真实实现属于装配歧义，启动即失败")
    void duplicateRealImplementationsFailFast() {
        assertThatThrownBy(() -> new ModelClientLocator(
                List.of(new RecordingRealClient(), new RecordingRealClient()), AgentTestSupport.environment("dev")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("没有可用实现时报 MODEL_NOT_CONFIGURED，不回落到 fake")
    void missingProviderClientIsConfiguredFailure() {
        ModelClientLocator locator = new ModelClientLocator(List.of(new RecordingRealClient()),
                AgentTestSupport.environment("prod"));

        assertThatThrownBy(() -> locator.forProvider("OPENAI"))
                .isInstanceOfSatisfying(ModelProviderException.class,
                        e -> assertThat(e.providerErrorCode()).isEqualTo("MODEL_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("Fake 脚本用尽时明确报错，不重复最后一条、也不返回空回答")
    void exhaustedScriptFailsLoudly() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(text("只有一条"));
        ModelRequest request = new ModelRequest("deepseek-flash",
                List.of(ModelMessage.user("问题")), List.of(), null, ThinkingMode.DISABLED, 512, null,
                Duration.ofSeconds(5));
        AgentContext context = AgentTestSupport.context(RunLimits.defaults());

        assertThat(client.complete(request, context).content()).isEqualTo("只有一条");
        assertThatThrownBy(() -> client.complete(request, context))
                .isInstanceOfSatisfying(ModelProviderException.class,
                        e -> assertThat(e.providerErrorCode()).isEqualTo(FakeErrorCode.SCRIPT_EXHAUSTED));
        // 每一次调用都被记下来，供"到底发了几次请求"的断言使用
        assertThat(client.callCount()).isEqualTo(2);
        assertThat(client.recordedRequests()).hasSize(2);
        assertThat(client.recordedRequests().get(0).model()).isEqualTo("deepseek-flash");
    }

    @Test
    @DisplayName("Fake 返回的 providerRequestId 带 FAKE- 前缀，不会被当成供应商回执")
    void fakeResultsAreMarkedAsSynthetic() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(text("本地脚本生成的回答"));
        ModelResponse response = client.complete(new ModelRequest("deepseek-flash",
                List.of(ModelMessage.user("问题")), List.of(), null, ThinkingMode.DISABLED, 512, null,
                Duration.ofSeconds(5)), AgentTestSupport.context(RunLimits.defaults()));

        assertThat(response.providerRequestId()).startsWith("FAKE-");
        assertThat(client.synthetic()).isTrue();
        assertThat(client.label()).isEqualTo(FakeModelProperties.DEFAULT_LABEL);
    }

    static final class RecordingRealClient implements ModelClient {

        @Override
        public String providerId() {
            return "DEEPSEEK";
        }

        @Override
        public ModelResponse complete(com.hfj.fitagent.agent.model.ModelRequest request,
                                      com.hfj.fitagent.agent.core.AgentContext context) {
            return text("真实供应商");
        }
    }
}
