package com.hfj.fitagent.modules.coach.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;

/**
 * run 的请求指纹：{@code SHA-256(conversationId | 规范化问题文本 | 语言/日期参数)}（设计 §7）。
 *
 * <p>它的唯一用途是回答"同一个幂等键背后是不是同一个请求"，两条纪律因此都是安全性的而非性能性的：
 * <ol>
 *   <li><b>输入里永不含凭据</b>：会话号、规范化问题文本、服务端推导的业务日期与语言标签之外什么都不带。
 *       API Key 一旦进了哈希，run 行就成了可反查的密钥指纹（§6.1：Key 不入库）。</li>
 *   <li><b>哈希的是规范化后的文本</b>：同一个问题带不同首尾空白必须是同一个指纹，
 *       否则用户重试一次就多烧一份预算。</li>
 * </ol>
 *
 * <p>长度上限由 {@code CoachProperties.maxQuestionChars} 在进这里之前把关；
 * 这里不截断——截断后再哈希会把两个不同的长问题折叠成同一指纹，那是更严重的错。
 */
public final class RequestHasher {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** 分段用控制字符：不可能出现在经过 trim 的问题文本里，因此 "a"+"b" 与 "ab"+"" 不会撞在一起。 */
    private static final char DELIMITER = (char) 0;

    private RequestHasher() {
    }

    /**
     * @param languageTag 语言标签（本轮由服务端固定，客户端不传；null 视为未指定）
     * @param businessDate 服务端按用户时区推导的业务日期（客户端不传日期，§AGENTS.md）
     */
    public static String hash(long conversationId, String normalizedQuestion,
                              String languageTag, LocalDate businessDate) {
        StringBuilder canonical = new StringBuilder(64 + (normalizedQuestion == null ? 0 : normalizedQuestion.length()));
        canonical.append(conversationId).append(DELIMITER)
                .append(normalizedQuestion == null ? "" : normalizedQuestion).append(DELIMITER)
                .append(languageTag == null || languageTag.isBlank() ? "-" : languageTag.trim()).append(DELIMITER)
                .append(businessDate == null ? "-" : businessDate.toString());
        return sha256Hex(canonical.toString());
    }

    /** 64 位小写十六进制，恰好填满 {@code CHAR(64)} 列。 */
    public static String sha256Hex(String value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // JVM 必然支持 SHA-256；真出现说明运行时被裁剪过，宁可失败也不要退化成一个可预测的摘要
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int b = digest[i] & 0xFF;
            out[i * 2] = HEX[b >>> 4];
            out[i * 2 + 1] = HEX[b & 0x0F];
        }
        return new String(out);
    }
}
