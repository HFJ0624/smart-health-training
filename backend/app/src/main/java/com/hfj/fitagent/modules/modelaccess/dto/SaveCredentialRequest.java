package com.hfj.fitagent.modules.modelaccess.dto;

/**
 * 保存/替换 API Key 的请求体（{@code PUT /api/model-credentials/{providerCode}}）。
 *
 * <p>{@code apiKey} 是明文入站字段：它只存在于本次调用的栈上，
 * 不落任何字段、不进日志、不出现在任何响应或异常文案里（设计 §6.1）。
 * 全局 JSON 配置是"未知字段一律拒绝"，因此这里<b>不能</b>出现 {@code base_url}、
 * {@code providerId}、{@code credentialVersion} 之类的服务端字段——多传即 4xx。
 *
 * @param apiKey 用户从供应商处取得的 Key
 * @param label  可选备注名（≤80 字符，与列宽一致）
 */
public record SaveCredentialRequest(String apiKey, String label) {
}
