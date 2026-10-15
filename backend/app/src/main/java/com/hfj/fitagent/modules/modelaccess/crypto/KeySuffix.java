package com.hfj.fitagent.modules.modelaccess.crypto;

/**
 * 给用户"认出我存的是哪把 Key"用的尾部标记（{@code model_credential.key_suffix}，最多 8 字符）。
 *
 * <p>两条规则：
 * <ol>
 *   <li>长度上限 8，与列宽一致（超宽会被 MySQL 拒绝，这里先拒，错误才可读）；</li>
 *   <li><b>永不返回整把 Key</b>：短 Key 时按 {@code len/2} 取尾，
 *       这样"Key 只有 6 位"这种极端情况也只会露出 3 位，而不是整把 Key 出现在页面上。</li>
 * </ol>
 *
 * <p>大小写逐字符保留（列是 utf8mb4_bin，与 wechat_open_id 同一口径）：尾部是密钥的一部分，
 * 折叠大小写会让用户认出错的那把。
 */
public final class KeySuffix {

    /** 列宽 VARCHAR(8)。 */
    public static final int MAX_CHARS = 8;

    private KeySuffix() {
    }

    /** 取明文 Key 的尾部标记；Key 太短时按比例再缩小，保证永不等于整把。 */
    public static String of(char[] rawKey) {
        if (rawKey == null || rawKey.length == 0) {
            return null;
        }
        int length = rawKey.length;
        // 永不超过一半，也永不超过列宽：8 字符的 Key 只给 4 字符，4 字符的 Key 只给 2 字符
        int take = Math.min(MAX_CHARS, length / 2);
        if (take <= 0) {
            // 长度 1 的 Key 取不到"半个字符"：宁可什么都不显示，也不显示整把
            return null;
        }
        String suffix = new String(rawKey, length - take, take);
        // 尾部若切到代理对中间会留下孤立代理项，落库会变成非法字符：直接丢掉那半个
        if (Character.isHighSurrogate(suffix.charAt(0)) || Character.isLowSurrogate(suffix.charAt(0))) {
            suffix = suffix.substring(1);
        }
        return suffix.isEmpty() ? null : suffix;
    }

    /** 校验外部传入的尾部（本轮没有写入路径，留给测试与将来的导入用）。 */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_CHARS) {
            throw new IllegalArgumentException("key_suffix 最多 " + MAX_CHARS + " 字符");
        }
        return trimmed;
    }
}
