package com.hfj.fitagent.modules.modelaccess;

import java.util.List;
import java.util.Set;

/**
 * modelaccess 的枚举字面量集中处。
 *
 * <p>实体里的枚举列一律声明为 {@code String} 而不是 Java enum：MySQL 的 ENUM 比较默认大小写不敏感，
 * 而项目约定"取值必须由服务端显式判定"（docs/p1-database.md §5 补充规则 1）。
 * 把合法取值写在这里，写入前校验、读取后比较都只引用这一处，避免散落的字符串。
 */
public final class Vocabulary {

    private Vocabulary() {
    }

    /** {@code model_provider.status} / {@code model_catalog.status}。 */
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final Set<String> ENABLED_STATUS = Set.of(STATUS_ACTIVE, STATUS_DISABLED);

    /** {@code model_credential.status}。 */
    public static final String CREDENTIAL_ACTIVE = "ACTIVE";
    public static final String CREDENTIAL_INVALID = "INVALID";
    public static final String CREDENTIAL_REVOKED = "REVOKED";
    public static final Set<String> CREDENTIAL_STATUS = Set.of(CREDENTIAL_ACTIVE, CREDENTIAL_INVALID, CREDENTIAL_REVOKED);

    /** {@code model_price.rate_tier}（协议 §7：峰谷档真实存在，预占恒按 PEAK）。 */
    public static final String RATE_PEAK = "PEAK";
    public static final String RATE_OFF_PEAK = "OFF_PEAK";
    public static final Set<String> RATE_TIER = Set.of(RATE_PEAK, RATE_OFF_PEAK);

    /**
     * 币种允许值：协议 §8 与 {@code model_price} 注释都只给出 CNY / USD 两种，
     * 因此这里按已核实的取值判等；出现第三种即拒绝，不猜汇率、不相减。
     */
    public static final String CURRENCY_CNY = "CNY";
    public static final String CURRENCY_USD = "USD";
    public static final Set<String> CURRENCY = Set.of(CURRENCY_CNY, CURRENCY_USD);

    /** {@code budget_reservation.status}。 */
    public static final String RESERVATION_RESERVED = "RESERVED";
    public static final String RESERVATION_SETTLED = "SETTLED";
    public static final String RESERVATION_UNCERTAIN = "UNCERTAIN";
    public static final String RESERVATION_RELEASED = "RELEASED";
    public static final Set<String> RESERVATION_STATUS =
            Set.of(RESERVATION_RESERVED, RESERVATION_SETTLED, RESERVATION_UNCERTAIN, RESERVATION_RELEASED);

    /** {@code budget_reservation.estimate_quality}。 */
    public static final String QUALITY_CONSERVATIVE = "CONSERVATIVE";
    public static final String QUALITY_ACTUAL = "ACTUAL";
    public static final String QUALITY_UNKNOWN = "UNKNOWN";
    public static final Set<String> ESTIMATE_QUALITY = Set.of(QUALITY_CONSERVATIVE, QUALITY_ACTUAL, QUALITY_UNKNOWN);

    /** {@code budget_policy.budget_timezone}：本轮固定值，没有任何写入路径（设计 §7 V15）。 */
    public static final String BUDGET_TIMEZONE = "Asia/Shanghai";

    /** 供应商码规范化：列保持 CI 排序规则，服务端先统一大写再精确匹配（与 exercise code 同一口径）。 */
    public static String canonicalProviderCode(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 币种规范化大写（ISO-4217 约定大写）。 */
    public static String canonicalCurrency(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** 模型码规范化：官方 model 取值是小写连字符形式，只做 trim + 转小写。 */
    public static String canonicalModelCode(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** 用于错误信息里列出允许值，顺序稳定便于测试断言。 */
    public static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }
}
