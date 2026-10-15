package com.hfj.fitagent.agent.trace;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轨迹文本脱敏：任何"形似密钥"的值都在离开 Runner 之前被替换。
 *
 * <p>为什么用文本级正则而不是只解析 JSON：arguments 的原文由模型生成，可能是合法 JSON、半个 JSON、
 * 或一段被模型抄进参数里的凭据片段。解析失败时也必须照样脱敏，否则"脱敏"会出现一个跳过分支——
 * 那正是泄露最常发生的地方。
 *
 * <p>覆盖范围：
 * <ul>
 *   <li>JSON 键名命中密钥类词表（key/secret/token/password/credential/authorization/apikey/sign/private/access 等）
 *       → 值替换为占位符；</li>
 *   <li>{@code sk-…} 形式的 Key 与 {@code Bearer …} 头形式；</li>
 *   <li>{@code reasoning_content}（协议 §5 明确禁止它出现在前端轨迹或普通应用日志）。</li>
 * </ul>
 * 结果再做长度截断——轨迹不是第二个消息存储。
 */
public final class TraceRedaction {

    public static final String MASK = "[已脱敏]";
    public static final int MAX_CHARS = 512;

    private static final Pattern JSON_PAIR = Pattern.compile(
            "\"(?<key>[^\"]{1,128})\"\\s*:\\s*(?<value>\"(?:[^\"\\\\]|\\\\.)*\"|\\[[^\\[\\]]*]|\\{[^{}]*}|[^,{}\\[\\]\\s]+)");

    /** 与 ToolExecutor 的身份字段词表同源思路，但这里更宽：宁可多遮，不可漏遮。 */
    private static final Pattern KEYISH_NAME = Pattern.compile(
            ".*(key|secret|token|password|passwd|credential|authorization|apikey|access[_-]?token|"
                    + "refresh[_-]?token|signature|sign|private[_-]?cert|cookie|sessionid|auth).*",
            Pattern.DOTALL);

    private static final Pattern RAW_API_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_\\-]{6,}\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]{4,}");
    private static final Pattern REASONING_PAIR = Pattern.compile(
            "\"reasoning_content\"\\s*:\\s*(?:\"(?:[^\"\\\\]|\\\\.)*\"|[^,{}\\[\\]\\s]+)");

    private TraceRedaction() {
    }

    /**
     * @param raw 任意文本（工具参数原文、结果摘要）
     * @return 脱敏并截断后的文本；输入为 null 时返回 null（调用方自行决定占位）
     */
    public static String redact(String raw) {
        if (raw == null) {
            return null;
        }
        String masked = maskJsonKeyishValues(raw);
        masked = REASONING_PAIR.matcher(masked).replaceAll("\"reasoning_content\":" + Matcher.quoteReplacement(MASK));
        masked = BEARER.matcher(masked).replaceAll(Matcher.quoteReplacement("Bearer " + MASK));
        masked = RAW_API_KEY.matcher(masked).replaceAll(Matcher.quoteReplacement(MASK));
        return truncate(masked, MAX_CHARS);
    }

    /** 摘要字段用：只截断，不假设它是 JSON。 */
    public static String summarize(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String masked = BEARER.matcher(RAW_API_KEY.matcher(raw).replaceAll(MASK))
                .replaceAll(Matcher.quoteReplacement("Bearer " + MASK));
        return truncate(masked, maxChars);
    }

    private static String maskJsonKeyishValues(String text) {
        Matcher matcher = JSON_PAIR.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 32);
        while (matcher.find()) {
            String key = matcher.group("key");
            if (isKeyish(key)) {
                matcher.appendReplacement(out, Matcher.quoteReplacement("\"" + key + "\":" + MASK));
            } else {
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean isKeyish(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.toLowerCase(Locale.ROOT);
        // 例外的业务字段：model_code / source_table 之类里含 "key" 子串的概率极低，
        // 但 model_price 之类若真出现误遮，代价只是轨迹少一个值，不影响回答——按"宁可多遮"处理。
        return KEYISH_NAME.matcher(normalized).matches();
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "…[截断]";
    }
}
