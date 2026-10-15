package com.hfj.fitagent.agent.core;

import java.util.Locale;

/**
 * 凭据<b>句柄</b>：只有定位信息，没有任何 Key 材料。
 *
 * <p>存在理由（docs/agent-v1-design.md §6.1 / B10）：Agent 上下文会穿过 Runner、工具、轨迹，
 * 如果它持有明文凭据，任何一处 toString / 日志都会泄露。这里连密文都不给——
 * 解密只发生在 DeepSeekClient 发出 HTTP 前的一行代码里，由 ProviderCredentialResolver 按句柄取。
 *
 * @param credentialId       model_credential.id
 * @param credentialVersion  run 创建时绑定的版本；执行中不悄悄换 Key
 * @param providerId         model_provider.provider_code（大写规范化，与列的 CI 排序规则同口径）
 */
public record CredentialHandle(long credentialId, int credentialVersion, String providerId) {

    public CredentialHandle {
        if (credentialId <= 0) {
            throw new IllegalArgumentException("credentialId 必须为正数");
        }
        if (credentialVersion <= 0) {
            throw new IllegalArgumentException("credentialVersion 必须为正数");
        }
        providerId = providerId == null ? "" : providerId.trim().toUpperCase(Locale.ROOT);
        if (providerId.isEmpty()) {
            throw new IllegalArgumentException("providerId 不能为空");
        }
    }

    /** 轨迹/日志用：能定位是哪一行凭据，但推不出任何密钥材料。 */
    @Override
    public String toString() {
        return "CredentialHandle[credentialId=" + credentialId + ", version=" + credentialVersion
                + ", provider=" + providerId + "]";
    }
}
