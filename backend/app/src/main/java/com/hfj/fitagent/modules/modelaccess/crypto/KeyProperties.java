package com.hfj.fitagent.modules.modelaccess.crypto;

import java.util.Base64;

/**
 * 密钥配置的静态校验：base64 的 32 字节，两个用途各一把。
 *
 * <p>刻意<b>不</b>做任何"派生"：设计 §6.1 要求加密密钥与指纹密钥是两个独立配置的秘密，
 * 从一个密钥推两个用途会让指纹变成密文的旁路信息，因此这里没有 HKDF/HMAC-expand 之类的自创 KDF，
 * 只有"解码 + 长度检查"。
 *
 * <p>校验失败抛 {@link IllegalStateException} 而不是 BizException：这是配置错误（运维问题），
 * 应当在启动自检或第一次使用时立刻炸出来，而不是被当成一次可重试的业务失败。
 */
public final class KeyProperties {

    /** AES-256 与 HMAC-SHA256 都按 32 字节密钥配置（设计 §6.1）。 */
    public static final int REQUIRED_BYTES = 32;

    private KeyProperties() {
    }

    /**
     * 解码并校验长度，只用于"配置对不对"的判断；返回值用完即擦除，不保存。
     *
     * @param property 配置项全名，出现在异常里便于定位（异常文本不含密钥内容）
     */
    public static void require(String base64, String property) {
        byte[] decoded = decode(base64, property);
        try {
            if (decoded.length != REQUIRED_BYTES) {
                throw new IllegalStateException(property + " 必须是 " + REQUIRED_BYTES + " 字节的 base64，实际 "
                        + decoded.length + " 字节");
            }
        } finally {
            wipe(decoded);
        }
    }

    /** 解出原始密钥字节；调用方负责在 finally 里 {@link #wipe(byte[])}。 */
    public static byte[] decode(String base64, String property) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(property + " 未配置");
        }
        final byte[] decoded;
        try {
            // 严格解码：非法字符直接失败，不用 MIME 解码器"顺手忽略"空白
            decoded = Base64.getDecoder().decode(base64.trim());
        } catch (RuntimeException e) {
            // 不带原始值：异常文本可能被日志采集
            throw new IllegalStateException(property + " 不是合法的 base64（" + e.getClass().getSimpleName() + "）", e);
        }
        return decoded;
    }

    /** 就地擦除：不能保证 GC 一定回收旧副本，但至少不让密钥在堆上多活一轮。 */
    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            java.util.Arrays.fill(bytes, (byte) 0);
        }
    }

    /** 就地擦除明文（char[] 版本，配合 {@code CredentialCipher} 的 char[] 接口）。 */
    public static void wipe(char[] chars) {
        if (chars != null) {
            java.util.Arrays.fill(chars, '\0');
        }
    }
}
