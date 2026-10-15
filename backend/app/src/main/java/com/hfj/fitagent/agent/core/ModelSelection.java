package com.hfj.fitagent.agent.core;

import com.hfj.fitagent.agent.model.ModelCapabilities;

import java.util.Locale;

/**
 * run 创建时绑定的模型选择（取值来自 {@code model_catalog}，docs/agent-v1-protocol.md §1）。
 *
 * <p>能力与输出上限由服务端从目录里读出并固化在 run 上，模型自己"声称"的能力一律不作数
 * （docs/agent-v1-design.md §11：客户端传来的模型能力不是可信值）。
 *
 * @param providerId      model_provider.provider_code
 * @param modelId         model_catalog.id
 * @param modelCode       实际写入请求体 {@code model} 字段的值（如 deepseek-flash）
 * @param capabilities    tools/vision/json 能力位
 * @param maxOutputTokens 目录里的输出上限，Runner 用它压住 max_tokens
 */
public record ModelSelection(String providerId,
                             long modelId,
                             String modelCode,
                             ModelCapabilities capabilities,
                             int maxOutputTokens) {

    public ModelSelection {
        providerId = providerId == null ? "" : providerId.trim().toUpperCase(Locale.ROOT);
        if (providerId.isEmpty()) {
            throw new IllegalArgumentException("providerId 不能为空");
        }
        if (modelCode == null || modelCode.isBlank()) {
            throw new IllegalArgumentException("modelCode 不能为空：取值必须来自 model_catalog");
        }
        modelCode = modelCode.trim();
        capabilities = capabilities == null ? ModelCapabilities.none() : capabilities;
        if (modelId <= 0) {
            throw new IllegalArgumentException("modelId 必须为正数");
        }
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须为正数（来自 model_catalog）");
        }
    }
}
