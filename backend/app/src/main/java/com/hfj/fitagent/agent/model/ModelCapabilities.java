package com.hfj.fitagent.agent.model;

/**
 * 供应商声明的能力位（docs/agent-v1-protocol.md §1 的表）。
 *
 * 值一律来自 {@code model_catalog}，适配器不写死：本轮唯一被证实的自相矛盾（v4-pro 是否支持图片）
 * 就说明"按记忆填能力"迟早会变成代码里的事实。
 */
public record ModelCapabilities(boolean supportsTools,
                                boolean supportsVision,
                                boolean supportsJson) {

    public static ModelCapabilities toolsOnly() {
        return new ModelCapabilities(true, false, true);
    }

    public static ModelCapabilities none() {
        return new ModelCapabilities(false, false, false);
    }
}
