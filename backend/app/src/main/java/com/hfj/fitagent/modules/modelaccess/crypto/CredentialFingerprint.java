package com.hfj.fitagent.modules.modelaccess.crypto;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.modelaccess.ModelAccessProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 凭据指纹：HMAC-SHA256，使用<b>另一把</b>独立配置的密钥（{@code fingerprint-key}）。
 *
 * <p>它唯一的作用是回答"这次提交的 Key 和已经存着的是不是同一把"，从而让"重复保存同一把 Key"
 * 不会把 {@code credential_version} 推高。落库形态是 {@code BINARY(32)} 的原始摘要，
 * 不做 hex/Base64 包装（列宽就是为原始字节设计的），也<b>永不</b>出现在任何响应里。
 *
 * <p>刻意不从主密钥派生：设计 §6.1 禁止"一个密钥两个用途"，指纹密钥与加密密钥是两个配置项，
 * 由 {@link ModelAccessProperties} 在构造期拒绝两者取值相同。
 */
@Component
public class CredentialFingerprint {

    /** HMAC-SHA256 原始摘要长度，等于 {@code key_fingerprint} 的 BINARY(32) 列宽。 */
    public static final int BYTES = 32;

    private static final String ALGORITHM = "HmacSHA256";

    private final ModelAccessProperties properties;

    public CredentialFingerprint(ModelAccessProperties properties) {
        this.properties = properties;
    }

    /** 计算指纹；密钥缺失属后端配置问题。 */
    public byte[] of(char[] rawKey) {
        if (rawKey == null || rawKey.length == 0) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "API Key 不能为空");
        }
        byte[] keyBytes = fingerprintKeyBytes();
        byte[] message = null;
        try {
            message = toUtf8(rawKey);
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "凭据指纹计算失败");
        } finally {
            KeyProperties.wipe(keyBytes);
            KeyProperties.wipe(message);
        }
    }

    /**
     * 定长比较（{@link MessageDigest#isEqual} 是常量时间的字节比较）。
     *
     * <p>指纹不是秘密，但"长度不符即 false"能避免把明显不是一代的记录拿来比。
     */
    public boolean matches(byte[] stored, char[] candidateKey) {
        if (stored == null || stored.length != BYTES || candidateKey == null || candidateKey.length == 0) {
            return false;
        }
        byte[] computed = of(candidateKey);
        try {
            return MessageDigest.isEqual(stored, computed);
        } finally {
            KeyProperties.wipe(computed);
        }
    }

    /** 已存指纹是否形状合法（用于读取路径的自检，不返回内容）。 */
    public static boolean isWellFormed(byte[] stored) {
        return stored != null && stored.length == BYTES;
    }

    private byte[] fingerprintKeyBytes() {
        if (!properties.fingerprintKeyConfigured()) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "后端未配置凭据指纹密钥");
        }
        try {
            return KeyProperties.decode(properties.fingerprintKey(), "fitagent.modelaccess.fingerprint-key");
        } catch (IllegalStateException e) {
            throw new BizException(ErrorCode.MODEL_NOT_CONFIGURED, "后端凭据指纹密钥配置非法");
        }
    }

    private static byte[] toUtf8(char[] chars) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        return out;
    }

    @Override
    public String toString() {
        // Arrays 不参与：绝不输出任何密钥材料
        return "CredentialFingerprint{HmacSHA256, " + BYTES + " 字节}";
    }

    /** 测试辅助：判断摘要数组是否为全零（不用于生产路径）。 */
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
