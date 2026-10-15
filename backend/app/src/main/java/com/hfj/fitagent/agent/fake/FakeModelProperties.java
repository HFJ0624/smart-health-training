package com.hfj.fitagent.agent.fake;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fake 模型开关（docs/agent-v1-design.md §13）。
 *
 * <p><b>两把锁</b>（沿用 dev-login 的做法）：
 * <ol>
 *   <li>{@code @Profile({"dev","test"})} + 本开关必须为 true，Bean 才会被创建；</li>
 *   <li>{@link FakeModelStartupGuard}：prod profile 下一旦探测到开关为 true，直接让应用启动失败。</li>
 * </ol>
 * 再加第三把：{@code ModelClientLocator} 在解析供应商时拒绝返回合成实现（运行期兜底），
 * 所以"生产构建静默连到假模型而不是 DeepSeek"在三个位置上都不可发生。
 */
@ConfigurationProperties(prefix = "fitagent.agent.fake-model")
public record FakeModelProperties(boolean enabled, String providerId, String label) {

    public static final String DEFAULT_PROVIDER_ID = "DEEPSEEK";
    /** 界面必须显示的模式标签（design §12：Fake 结果与真实供应商结果分开标注）。 */
    public static final String DEFAULT_LABEL = "FAKE_MODEL";

    public FakeModelProperties {
        providerId = providerId == null || providerId.isBlank()
                ? DEFAULT_PROVIDER_ID
                : providerId.trim().toUpperCase(java.util.Locale.ROOT);
        label = label == null || label.isBlank() ? DEFAULT_LABEL : label.trim();
    }

    public static FakeModelProperties disabled() {
        return new FakeModelProperties(false, null, null);
    }
}
