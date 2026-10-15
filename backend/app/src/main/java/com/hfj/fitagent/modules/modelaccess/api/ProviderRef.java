package com.hfj.fitagent.modules.modelaccess.api;

/**
 * 白名单供应商的读取结果（V10 的对外形状）。
 *
 * <p>{@code baseUrl} 只能来自这张表：任何接口都不接受客户端改写外呼地址，
 * 否则服务端就成了 SSRF 跳板（V10 注释）。调用方拿到的是<b>值</b>而不是实体，
 * 因此实体不会跨模块流动。
 *
 * @param providerId        model_provider.id
 * @param providerCode      规范化大写的供应商码
 * @param displayName       界面显示名
 * @param baseUrl           外呼基地址（不含 /v1 前缀，协议 §2）
 * @param adapterCode       决定用哪个协议实现
 * @param balanceSupported  FALSE 时余额网关不外呼
 */
public record ProviderRef(long providerId,
                          String providerCode,
                          String displayName,
                          String baseUrl,
                          String adapterCode,
                          boolean balanceSupported) {
}
