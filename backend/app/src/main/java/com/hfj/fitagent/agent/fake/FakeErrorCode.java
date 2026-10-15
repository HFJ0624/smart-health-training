package com.hfj.fitagent.agent.fake;

import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ProviderErrorKind;

/**
 * Fake 模式自己的错误码（与 DeepSeek 的码分开，避免把"本地脚本用尽"伪装成供应商故障）。
 */
public final class FakeErrorCode {

    /** 脚本队列已用尽：还想要更多调用，但没有预置响应了。 */
    public static final String SCRIPT_EXHAUSTED = "FAKE_SCRIPT_EXHAUSTED";
    /** prod 环境探测到 Fake：启动失败。 */
    public static final String PRODUCTION_FORBIDDEN = "FAKE_MODEL_FORBIDDEN_IN_PROD";
    /** 运行期解析到合成实现却被用于生产：调用失败。 */
    public static final String SYNTHETIC_REJECTED = "SYNTHETIC_MODEL_REJECTED";

    private FakeErrorCode() {
    }

    public static ModelProviderException scriptExhausted(int requested) {
        return new ModelProviderException(ProviderErrorKind.CONFIGURATION_REJECTED, SCRIPT_EXHAUSTED,
                "Fake 脚本只有 " + requested + " 条预置响应，测试需要更少的模型调用次数或更多预置响应", false, null);
    }

    public static IllegalStateException productionForbidden() {
        return new IllegalStateException("prod 环境不允许启用 Fake 模型（fitagent.agent.fake-model.enabled=true）。"
                + "生产构建必须连接真实供应商，或如实报 MODEL_NOT_CONFIGURED——"
                + "用一个假模型冒充 DeepSeek 会让所有费用与验收结论失真");
    }
}
