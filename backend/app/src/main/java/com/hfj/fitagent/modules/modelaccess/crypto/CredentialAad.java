package com.hfj.fitagent.modules.modelaccess.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * GCM 的认证附加数据（AAD）：{@code userId|providerId|credentialVersion}（设计 §6.1）。
 *
 * <p>绑定这三样的目的：密文行属于"某个用户的某个供应商的某个凭据版本"，
 * 把 A 行的 ciphertext/nonce 复制到 B 行会因 AAD 不匹配而解密失败——
 * 这不是防写入（写入路径本来就只走应用），而是让"事后搬运密文"这种篡改在读取时必然暴露。
 *
 * <p>AAD 不进密文、不加密，它是认证标签的输入之一；因此它本身不是秘密，可以出现在日志里
 * （本项目的日志仍只打 id，因为没必要）。
 *
 * @param userId             所属用户
 * @param providerId         所属供应商
 * @param credentialVersion  该行的凭据版本号；替换一次即变化，run 绑定的正是它
 */
public record CredentialAad(long userId, long providerId, int credentialVersion) {

    public CredentialAad {
        Objects.requireNonNull(credentialVersion, "credentialVersion 不能为空");
        if (credentialVersion <= 0) {
            throw new IllegalArgumentException("credential_version 必须大于 0");
        }
    }

    /** 逐字符固定的编码，不做任何本地化或大小写处理。 */
    public byte[] bytes() {
        return (userId + "|" + providerId + "|" + credentialVersion).getBytes(StandardCharsets.UTF_8);
    }
}
