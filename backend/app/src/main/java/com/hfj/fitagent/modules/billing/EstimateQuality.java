package com.hfj.fitagent.modules.billing;

import java.util.Set;

/**
 * {@code budget_reservation.estimate_quality} 的字面量。
 *
 * <p>用 String 而不是 Java enum：MySQL ENUM 比较默认大小写不敏感，项目要求取值显式判定
 * （docs/p1-database.md §5 补充规则 1），因此列在实体里是 String，合法性由这里把关。
 */
public final class EstimateQuality {

    /** 预占时的保守估算（输入全按未命中价、输出按 max_output_tokens 全价、档位取 PEAK）。 */
    public static final String CONSERVATIVE = "CONSERVATIVE";
    /** 已按本次真实 usage 核算（仍不称为"供应商账单"，供应商结算记录才是最终依据）。 */
    public static final String ACTUAL = "ACTUAL";
    /** usage 不明：保留估算额，既不释放也不当已花。 */
    public static final String UNKNOWN = "UNKNOWN";

    public static final Set<String> ALLOWED = Set.of(CONSERVATIVE, ACTUAL, UNKNOWN);

    private EstimateQuality() {
    }
}
