package com.hfj.fitagent.modules.modelaccess.crypto;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.ModelAccessProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 凭据封套：AES-256-GCM，只用 JDK，不引第三方密码学库（设计 §6.1）。
 *
 * <p>三条不可让步的规则：
 * <ol>
 *   <li><b>每次加密新取 12 字节 SecureRandom nonce</b>。GCM 在同一密钥下重用 nonce 会直接破坏保密性与认证，
 *       所以 nonce 是 {@link SealedCredential} 的一部分并被单独落列，绝不做成常量或计数器；</li>
 *   <li><b>AAD = userId|providerId|credentialVersion</b>（{@link CredentialAad}）。密文与 nonce 搬到另一行、
 *       或版本号被改动后都解不开，"解不开"是这里唯一的成功路径；</li>
 *   <li><b>解密必须认证通过才返回</b>。任何认证失败都抛 {@link ErrorCode#CREDENTIAL_INVALID}，
 *       不存在"返回乱码继续跑"的分支。</li>
 * </ol>
 *
 * <p>密钥不进字段：本类只持有配置引用（{@link ModelAccessProperties}），主密钥每次使用时从配置解码到
 * 局部变量并在 finally 擦除。明文 Key 一律以 {@code char[]} 进出，同样在 finally 擦除本类产生的副本；
 * 需要长期保存明文的调用方（供应商外呼路径）自行负责其生命周期。
 */
@Component
public class CredentialCipher {

    /** GCM 推荐 nonce 长度，也是 {@code model_credential.nonce} 的 BINARY(12) 列宽。 */
    public static final int NONCE_BYTES = 12;
    /** GCM 认证标签长度（位）。 */
    public static final int TAG_BITS = 128;
    /** 标签字节数，用于说明"密文长度 = 明文长度 + 16"。 */
    public static final int TAG_BYTES = TAG_BITS / 8;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final Logger log = LoggerFactory.getLogger(CredentialCipher.class);

    private final ModelAccessProperties properties;
    /** 随机器不是密钥材料，可以作为字段长期持有。 */
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialCipher(ModelAccessProperties properties) {
        this.properties = properties;
    }

    /**
     * 加密明文 Key。
     *
     * @param rawKey 明文 Key 字符；本方法不擦除调用方的数组（所有权在调用方），但擦除自己派生的字节副本
     * @param aad    绑定三元组
     * @return 密文（含 16 字节标签）与本次的新 nonce
     */
    public SealedCredential encrypt(char[] rawKey, CredentialAad aad) {
        if (rawKey == null || rawKey.length == 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "API Key 不能为空");
        }
        byte[] keyBytes = masterKeyBytes();
        byte[] plain = null;
        try {
            plain = toUtf8(rawKey);
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.bytes());
            byte[] ciphertext = cipher.doFinal(plain);
            return new SealedCredential(ciphertext, nonce);
        } catch (GeneralSecurityException e) {
            // 只记异常类型：GCM 的异常文本可能含参数材料
            log.warn("凭据加密失败 type={}", e.getClass().getSimpleName());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭据加密失败");
        } finally {
            KeyProperties.wipe(keyBytes);
            KeyProperties.wipe(plain);
        }
    }

    /**
     * 解密。密文被改、nonce 错位（拿了别的行的 nonce）、AAD 三元组不符——都只会得到一个异常。
     *
     * @return 明文 Key 字符；调用方用完请 {@link KeyProperties#wipe(char[])}
     */
    public char[] decrypt(byte[] ciphertext, byte[] nonce, CredentialAad aad) {
        if (ciphertext == null || ciphertext.length <= TAG_BYTES) {
            // 比标签还短的"密文"不可能是合法封套，直接按无效凭据处理，不进 Cipher
            throw invalid("密文长度不合法");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw invalid("nonce 长度不合法");
        }
        byte[] keyBytes = masterKeyBytes();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM),
                    // nonce 数组直接交给 GCMParameterSpec：任何长度不符都在这里炸，不做补齐
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.bytes());
            byte[] plain = cipher.doFinal(ciphertext);
            try {
                return fromUtf8(plain);
            } catch (CharacterCodingException e) {
                // 认证已经通过却解不出 UTF-8：视为封套被破坏，不返回半个明文
                throw invalid("明文编码非法");
            } finally {
                KeyProperties.wipe(plain);
            }
        } catch (GeneralSecurityException e) {
            // AEADBadTagException 走这里：篡改/错 nonce/错 AAD/错密钥的唯一出口
            throw invalid("认证标签校验未通过");
        } finally {
            KeyProperties.wipe(keyBytes);
        }
    }

    /** 主密钥缺失是后端配置问题，与"用户凭据无效"要能区分开。 */
    private byte[] masterKeyBytes() {
        if (!properties.masterKeyConfigured()) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "后端未配置凭据加密主密钥");
        }
        try {
            return KeyProperties.decode(properties.masterKey(), "fitagent.modelaccess.master-key");
        } catch (IllegalStateException e) {
            // 只记异常类型，不记 message：主密钥的解码异常有可能把入参片段带出来
            log.warn("主密钥配置非法 type={}", e.getClass().getSimpleName());
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "后端凭据加密主密钥配置非法");
        }
    }

    private static BizException invalid(String reason) {
        // reason 只描述结构问题，绝不含材料内容
        log.warn("凭据解密被拒绝: {}", reason);
        return new BizException(ErrorCode.CREDENTIAL_INVALID, "模型 API Key 无法解密，请重新配置");
    }

    /** char[] → UTF-8 字节，不经过 {@code String}（String 不可擦除）。 */
    private static byte[] toUtf8(char[] chars) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        return out;
    }

    private static char[] fromUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
        char[] out = new char[decoded.remaining()];
        decoded.get(out);
        return out;
    }

    /** 本类的字符串表示不含任何材料。 */
    @Override
    public String toString() {
        return "CredentialCipher{AES-256-GCM, nonceBytes=" + NONCE_BYTES + ", tagBits=" + TAG_BITS + "}";
    }

    /** 便于测试断言"密钥数组确实被擦过"的辅助：仅暴露语义，不暴露内容。 */
    static boolean allZero(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
