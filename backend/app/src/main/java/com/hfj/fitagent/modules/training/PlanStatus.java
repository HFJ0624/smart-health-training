package com.hfj.fitagent.modules.training;

/**
 * training_plan.status。状态机见 docs/p1-scope.md §3.4：
 *
 * <pre>
 * DRAFT --activate--> ACTIVE --archive--> ARCHIVED
 *   |--discard--> DISCARDED                '--revise--> 同组新 DRAFT(revision_no=max+1)
 * 任意状态 --copy--> 新计划组 DRAFT(revision_no=1)
 * </pre>
 *
 * 字面量与数据库 ENUM 定义完全一致（docs/p1-database.md §3.6）。
 * 按 docs/p1-database.md §5 补充规则 1，契约层只接受大写字面量：{@code "draft"} 一律拒绝，
 * 否则前后端对"大小写是否等价"的口径会分叉。
 */
public enum PlanStatus {

    DRAFT,
    ACTIVE,
    ARCHIVED,
    DISCARDED;

    public String code() {
        return name();
    }

    /** 严格解析：大小写不符或未知字面量返回 null，由调用方转成 INVALID_ENUM_VALUE。 */
    public static PlanStatus parseStrict(String raw) {
        if (raw == null) {
            return null;
        }
        for (PlanStatus status : values()) {
            if (status.name().equals(raw)) {
                return status;
            }
        }
        return null;
    }

    /** 数据库读回来的字符串比较用这个，避免把 null 状态当成某种已知状态。 */
    public boolean matches(String raw) {
        return name().equals(raw);
    }
}
