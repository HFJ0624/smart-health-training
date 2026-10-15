package com.hfj.fitagent.modules.modelaccess.api;

/**
 * 选中的模型目录行（V11 的对外形状）：模型 ID、能力开关与上限都只可能来自这张表。
 *
 * <p>设计 §5 第 8 条要求这些数字不写死在代码里；读不到 ACTIVE 行时
 * {@code ProviderCatalogService} 抛 {@code MODEL_NOT_CONFIGURED}，<b>没有</b>内置默认模型这条路径。
 *
 * @param modelId           model_catalog.id
 * @param modelCode         请求体 model 字段取值（规范化小写）
 * @param displayName       界面显示名（官方"实际模型版本"）
 * @param supportsVision    本轮无图片入口，仅作真实登记
 * @param supportsTools     不支持工具调用的模型不能进入执行循环
 * @param supportsJson      是否支持 JSON Output
 * @param maxContextTokens  上下文窗口上限（历史裁剪用，不用于放宽任何业务阈值）
 * @param maxOutputTokens   供应商单次输出硬上限（保守预占用的是 budget_policy.max_output_tokens，不是这个）
 * @param sourceCheckedOn   能力核查日期（调用方可据此判断"这条事实有多旧"）
 */
public record ModelSelection(long providerId,
                             String providerCode,
                             String adapterCode,
                             long modelId,
                             String modelCode,
                             String displayName,
                             boolean supportsVision,
                             boolean supportsTools,
                             boolean supportsJson,
                             int maxContextTokens,
                             int maxOutputTokens,
                             java.time.LocalDate sourceCheckedOn) {
}
