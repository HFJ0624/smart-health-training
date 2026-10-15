package com.hfj.fitagent.agent.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 执行循环的工程闸门（docs/agent-v1-design.md §3）。
 *
 * <p><b>这些数字不是产品规则，也不是健康阈值</b>：它们只回答"一次提问最多允许烧多少次远程调用、
 * 多大结果、多久时间"，用来给预算与 Worker 一个可证明的上界。默认值取设计文档的初值，
 * 全部由 {@code fitagent.agent.limits.*} 覆盖；配置缺失或非正数时回落到默认值（而不是回落到 0 = 永不调用）。
 *
 * <p>{@code deadlineSeconds} 与 protocol §9 相关：官方会为非流式响应持续返回空行保活、最坏 10 分钟才关连接，
 * 所以 HTTP 超时必须受本 run 剩余时间约束，而剩余时间由本字段推导。
 */
@ConfigurationProperties(prefix = "fitagent.agent.limits")
public record RunLimits(int maxModelCalls,
                        int maxToolCalls,
                        int maxToolsPerRound,
                        int maxResultBytes,
                        int maxOutputTokens,
                        long deadlineSeconds) {

    public static final int DEFAULT_MAX_MODEL_CALLS = 4;
    public static final int DEFAULT_MAX_TOOL_CALLS = 8;
    public static final int DEFAULT_MAX_TOOLS_PER_ROUND = 4;
    public static final int DEFAULT_MAX_RESULT_BYTES = 8192;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 1024;
    public static final long DEFAULT_DEADLINE_SECONDS = 120L;

    public RunLimits {
        maxModelCalls = positive(maxModelCalls, DEFAULT_MAX_MODEL_CALLS);
        maxToolCalls = positive(maxToolCalls, DEFAULT_MAX_TOOL_CALLS);
        maxToolsPerRound = positive(maxToolsPerRound, DEFAULT_MAX_TOOLS_PER_ROUND);
        maxResultBytes = positive(maxResultBytes, DEFAULT_MAX_RESULT_BYTES);
        maxOutputTokens = positive(maxOutputTokens, DEFAULT_MAX_OUTPUT_TOKENS);
        deadlineSeconds = positive(deadlineSeconds, DEFAULT_DEADLINE_SECONDS);
        if (maxToolsPerRound > maxToolCalls) {
            // 单轮上限超过总上限会让"轮内闸门"变成死代码，配置写错时宁可启动即说明
            throw new IllegalStateException("fitagent.agent.limits.max-tools-per-round 不得大于 max-tool-calls");
        }
    }

    public static RunLimits defaults() {
        return new RunLimits(0, 0, 0, 0, 0, 0L);
    }

    /** 还能不能再发一次远程调用。每次调用都独立过预算闸门，本方法只管次数闸门。 */
    public boolean modelCallAllowed(int alreadyUsed) {
        return alreadyUsed < maxModelCalls;
    }

    /** 本轮是不是最后一次允许的调用——Runner 据此考虑用 tool_choice=none 收尾。 */
    public boolean isLastAllowedCall(int alreadyUsed) {
        return alreadyUsed == maxModelCalls - 1;
    }

    public boolean toolCallAllowed(int alreadyExecuted) {
        return alreadyExecuted < maxToolCalls;
    }

    public boolean toolAllowedInRound(int alreadyInRound) {
        return alreadyInRound < maxToolsPerRound;
    }

    /** 输出上限：RunLimits 与目录上限取小者，代码里不允许"按模型心情放大"。 */
    public int effectiveMaxOutputTokens(int catalogMaxOutputTokens) {
        return Math.min(maxOutputTokens, positive(catalogMaxOutputTokens, DEFAULT_MAX_OUTPUT_TOKENS));
    }

    public java.time.Duration deadline() {
        return java.time.Duration.ofSeconds(deadlineSeconds);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }
}
