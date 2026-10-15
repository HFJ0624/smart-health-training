package com.hfj.fitagent.agent.model.deepseek;

import com.hfj.fitagent.agent.model.ThinkingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置层对思考模式与地址的启动期拒绝（docs/agent-v1-protocol.md §2、§5）。
 *
 * <p>这一层拒绝的意义：@ConfigurationProperties 记录在绑定时构造，构造抛错 = 应用起不来。
 * 与其让每个带 tools 的请求都换回一个 400（并且已经预占过预算），不如在启动时就炸。
 */
class DeepSeekPropertiesTest {

    @Test
    @DisplayName("缺省值就是文档口径：https://api.deepseek.com + 不带 /v1 的 /chat/completions")
    void defaultsFollowTheProtocol() {
        DeepSeekProperties properties = DeepSeekProperties.defaults();

        assertThat(properties.baseUrl()).isEqualTo("https://api.deepseek.com");
        assertThat(properties.chatCompletionsPath()).isEqualTo("/chat/completions");
        assertThat(properties.chatCompletionsUri()).isEqualTo("https://api.deepseek.com/chat/completions");
        assertThat(properties.thinkingMode()).isEqualTo(ThinkingMode.DISABLED);
        assertThat(properties.maxOutputTokensCap()).isEqualTo(393216);
    }

    @Test
    @DisplayName("thinking=enabled 启动即失败，并引用 agent-v1-protocol.md §5 说明原因")
    void thinkingEnabledFailsStartup() {
        assertThatThrownBy(() -> new DeepSeekProperties(null, null, "enabled", null, null, null))
                .isInstanceOfSatisfying(IllegalStateException.class, e -> {
                    assertThat(e.getMessage()).contains("agent-v1-protocol.md");
                    assertThat(e.getMessage()).contains("reasoning_content");
                    assertThat(e.getMessage()).contains("400");
                });
    }

    @Test
    @DisplayName("thinking 写成别的值也拒绝（不允许'拼错就默认开启'）")
    void unknownThinkingValueFailsStartup() {
        assertThatThrownBy(() -> new DeepSeekProperties(null, null, "on", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("base-url 以 /v1 结尾被拒绝：未核实的前缀不能悄悄进入外呼地址")
    void baseUrlWithV1SuffixIsRejected() {
        assertThatThrownBy(() -> new DeepSeekProperties("https://api.deepseek.com/v1", null, null, null, null, null))
                .isInstanceOfSatisfying(IllegalStateException.class,
                        e -> assertThat(e.getMessage()).contains("/v1"));
    }

    @Test
    @DisplayName("非 https 的 base-url 被拒绝：Bearer Key 不走明文链路")
    void insecureBaseUrlIsRejected() {
        assertThatThrownBy(() -> new DeepSeekProperties("http://api.deepseek.com", null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DeepSeekProperties("https://user:pass@api.deepseek.com", null, null,
                null, null, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("网关代理前缀（非 /v1）是允许的，尾部斜杠被规范化")
    void proxyBasePathIsAccepted() {
        DeepSeekProperties properties = new DeepSeekProperties("https://gw.internal/deepseek/", null, null,
                null, null, null);

        assertThat(properties.chatCompletionsUri()).isEqualTo("https://gw.internal/deepseek/chat/completions");
    }

    @Test
    @DisplayName("超时可配置，但默认值受 run 截止时间压住（见 wire format 测试）")
    void timeoutsHaveDocumentedDefaults() {
        DeepSeekProperties properties = DeepSeekProperties.defaults();

        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        // 读取上限必须明显小于 run 的 120s 截止：官方为非流式响应持续吐空行保活、最坏 10 分钟才关连接
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }
}
