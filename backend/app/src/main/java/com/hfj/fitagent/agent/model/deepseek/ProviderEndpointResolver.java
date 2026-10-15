package com.hfj.fitagent.agent.model.deepseek;

/**
 * 外呼地址端口。
 *
 * <p>为什么需要它（docs/agent-v1-design.md §7 V10）：{@code model_provider.base_url} 才是权威白名单值，
 * 由运维写入、任何接口都不接受客户端改写——"由输入决定外呼地址"会把服务端变成 SSRF 跳板。
 * 适配器因此不能只信自己的配置默认值，必须允许 modelaccess 按 providerId 提供表里的地址。
 *
 * <p>实现方返回的值仍要经过 {@link DeepSeekProperties#chatCompletionsUri(String)} 的校验
 * （https、无查询串/片段/用户信息、不允许裸 /v1），所以这张表写错也不会把 Key 发到 http 地址。
 *
 * <p>没有实现（Bean 缺失）时回落到 {@code fitagent.agent.model.deepseek.base-url} 的默认值。
 */
public interface ProviderEndpointResolver {

    /**
     * @param providerId model_provider.provider_code（大写）
     * @return 该供应商的基地址（不含 {@code /chat/completions}）；返回 null 表示"用配置默认值"
     */
    String baseUrl(String providerId);
}
