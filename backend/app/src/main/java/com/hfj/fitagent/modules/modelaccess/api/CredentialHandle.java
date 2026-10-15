package com.hfj.fitagent.modules.modelaccess.api;

/**
 * 可用的凭据句柄：足以定位并解密一把 Key，但<b>不含任何密钥材料</b>。
 *
 * <p>四个字段同时也是 GCM 的 AAD 输入（{@code userId|providerId|credentialVersion}），
 * 所以句柄写错任何一个都只会得到"解密失败"，不会解出另一把 Key。
 *
 * <p>run 创建时绑定 {@code credentialVersion}：执行中用户换了 Key（版本 +1）后，
 * 用旧句柄解密会失败，从而落实"执行中不悄悄换 Key"（设计 §6.1）。
 *
 * <p>{@code userId} 在句柄里是必需的：它既是 AAD 的一部分，也是所有查询的归属条件——
 * 少了它，任何一次读取都可能变成跨用户读取。
 *
 * @param userId             所属用户（认证上下文）
 * @param credentialId       model_credential.id
 * @param credentialVersion  凭据版本号
 * @param providerId         model_provider.id
 */
public record CredentialHandle(long userId, long credentialId, int credentialVersion, long providerId) {

    public CredentialHandle {
        if (credentialVersion <= 0) {
            throw new IllegalArgumentException("credential_version 必须大于 0");
        }
    }

    @Override
    public String toString() {
        // 明确只打标识：句柄将来会出现在 run 的日志里，这里定好形状防止日后加字段带出材料
        return "CredentialHandle{user=" + userId + ", credential=" + credentialId
                + ", version=" + credentialVersion + ", provider=" + providerId + "}";
    }
}
