package com.hfj.fitagent.common.util;

import java.util.Locale;
import java.util.UUID;

/**
 * 标识符规范化口径集中在此，对应 docs/p1-database.md §5 的大小写规则。
 * 规则不同是因为语义不同：外部身份必须逐字符精确，客户端幂等标识必须大小写等价。
 */
public final class Ids {

    private Ids() {
    }

    /** 服务端生成的 UUID 统一小写，写入 plan_group_uuid 等列。 */
    public static String newUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * 客户端 UUID 规范化：解析成 UUID 再输出小写，格式非法即抛 IllegalArgumentException（由上层转字段错误）。
     * 列排序规则保持大小写不敏感作为兜底，两者叠加保证同一条逻辑记录只有一个索引键。
     */
    public static String canonicalUuid(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("UUID 不能为空");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("UUID 不能为空");
        }
        return UUID.fromString(trimmed).toString();
    }

    /** 动作字典代码：大写规范化后精确匹配，命中不了就是错误，不做近似匹配。 */
    public static String canonicalExerciseCode(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    /**
     * 微信 appid / openid / unionid：只 trim，绝不折叠大小写。
     * 这些串由微信生成且大小写有意义，折叠会把不同用户判成同一账号。
     */
    public static String exactExternalId(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        return v.isEmpty() ? null : v;
    }

    public static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
