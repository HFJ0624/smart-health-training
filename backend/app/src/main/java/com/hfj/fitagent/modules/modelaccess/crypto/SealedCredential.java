package com.hfj.fitagent.modules.modelaccess.crypto;

import java.util.Arrays;

/**
 * 一次加密的产物：密文（含 16 字节 GCM 认证标签）与本次新取的 12 字节 nonce。
 *
 * <p>两者分别落 {@code model_credential.ciphertext} 与 {@code nonce} 两列，不拼成单个 blob：
 * nonce 列是 {@code BINARY(12)}，长度可被数据库约束，密文列才是变长 BLOB。
 *
 * <p>{@link #toString()} 刻意只输出长度——密钥封套材料一旦进日志就是事故，
 * 而这个类最容易出现在字符串拼接与异常信息里。
 */
public record SealedCredential(byte[] ciphertext, byte[] nonce) {

    public SealedCredential {
        if (ciphertext == null || ciphertext.length == 0) {
            throw new IllegalArgumentException("密文不能为空");
        }
        if (nonce == null || nonce.length != CredentialCipher.NONCE_BYTES) {
            throw new IllegalArgumentException("nonce 必须是 " + CredentialCipher.NONCE_BYTES + " 字节");
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SealedCredential other
                && Arrays.equals(ciphertext, other.ciphertext)
                && Arrays.equals(nonce, other.nonce);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(ciphertext) + Arrays.hashCode(nonce);
    }

    @Override
    public String toString() {
        return "SealedCredential{ciphertextBytes=" + ciphertext.length + ", nonceBytes=" + nonce.length + "}";
    }
}
