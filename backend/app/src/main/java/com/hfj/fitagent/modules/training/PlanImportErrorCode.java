package com.hfj.fitagent.modules.training;

/**
 * 导入 / 计划聚合校验的**字段级**错误码常量。
 *
 * <p>为什么不是 {@code ErrorCode} 的枚举成员：一个请求会同时命中几十种字段问题，
 * 若每种都占一个响应码位，响应码就失去了"这一类失败"的聚合语义；
 * 契约里 HTTP 状态由外层信封码决定（422 {@code PLAN_IMPORT_INVALID}），
 * 字段级码只出现在 {@code errors[].code} / {@code warnings[].code} 里供页面逐行高亮。
 * 因此两套码分开维护：信封码在 {@code common.error.ErrorCode}，字段码在这里。
 *
 * <p>码值与 {@code docs/p1-plan-import.md} §2–§8、{@code docs/p1-api.md} §2 逐条对应，
 * 新增码必须同步那两份文档。
 *
 * <p>注：{@code ErrorCode} 的注释提到本类位于 {@code modules/training/import} 包，
 * 但 {@code import} 是 Java 关键字，不能作为包名，因此该类与校验器同放在
 * {@code com.hfj.fitagent.modules.training}。
 */
public final class PlanImportErrorCode {

    // ---- 结构 / 标量层（p1-plan-import §2–§4、§8） ----
    public static final String REQUIRED = "REQUIRED";
    public static final String TOO_LONG = "TOO_LONG";
    public static final String INVALID_TYPE = "INVALID_TYPE";
    public static final String INVALID_DATE_FORMAT = "INVALID_DATE_FORMAT";
    public static final String INVALID_ENUM_VALUE = "INVALID_ENUM_VALUE";
    public static final String INVALID_WEEKDAY = "INVALID_WEEKDAY";
    public static final String MUST_BE_POSITIVE = "MUST_BE_POSITIVE";
    public static final String NEGATIVE_LOAD = "NEGATIVE_LOAD";
    public static final String TOO_MANY_DECIMALS = "TOO_MANY_DECIMALS";
    /** 超出数据库列容量（SMALLINT/INT UNSIGNED 上界）。文档未列，作为 REST_OUT_OF_RANGE 同族的上程防护。 */
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";

    // ---- 跨字段（§2、§4） ----
    public static final String REST_OUT_OF_RANGE = "REST_OUT_OF_RANGE";
    public static final String REPS_MAX_LT_MIN = "REPS_MAX_LT_MIN";
    public static final String ENDS_BEFORE_STARTS = "ENDS_BEFORE_STARTS";

    // ---- 计划日 / 条目结构（§3、§8） ----
    public static final String DUPLICATE_WEEKDAY = "DUPLICATE_WEEKDAY";
    public static final String DUPLICATE_POSITION = "DUPLICATE_POSITION";
    public static final String REST_DAY_HAS_ITEMS = "REST_DAY_HAS_ITEMS";
    public static final String EMPTY_TRAINING_DAY = "EMPTY_TRAINING_DAY";
    public static final String TOO_MANY_DAYS = "TOO_MANY_DAYS";
    public static final String TOO_MANY_ITEMS = "TOO_MANY_ITEMS";
    public static final String TOO_MANY_ITEMS_TOTAL = "TOO_MANY_ITEMS_TOTAL";

    // ---- 字典（§4） ----
    public static final String EXERCISE_NOT_FOUND = "EXERCISE_NOT_FOUND";
    public static final String EXERCISE_DISABLED = "EXERCISE_DISABLED";

    // ---- 类型 × 字段矩阵（§5） ----
    public static final String STRENGTH_DURATION_FORBIDDEN = "STRENGTH_DURATION_FORBIDDEN";
    public static final String CARDIO_SETS_FORBIDDEN = "CARDIO_SETS_FORBIDDEN";
    public static final String CARDIO_REPS_FORBIDDEN = "CARDIO_REPS_FORBIDDEN";
    public static final String CARDIO_LOAD_FORBIDDEN = "CARDIO_LOAD_FORBIDDEN";
    public static final String CARDIO_REST_FORBIDDEN = "CARDIO_REST_FORBIDDEN";
    public static final String MOBILITY_LOAD_FORBIDDEN = "MOBILITY_LOAD_FORBIDDEN";
    public static final String MOBILITY_NO_TARGET = "MOBILITY_NO_TARGET";

    // ---- 字段来源控制（§2、§7） ----
    public static final String UNKNOWN_FIELD = "UNKNOWN_FIELD";
    public static final String FORBIDDEN_SERVER_FIELD = "FORBIDDEN_SERVER_FIELD";
    public static final String UNSUPPORTED_SCHEMA_VERSION = "UNSUPPORTED_SCHEMA_VERSION";

    // ---- 警告（不阻断，§8） ----
    public static final String POSITION_RENUMBERED = "POSITION_RENUMBERED";

    /** 顶层 notes：P1 无对应列，与其接受后丢弃，不如在预览阶段就报错（阻断）。 */
    public static final String PLAN_NOTES_UNSUPPORTED = "PLAN_NOTES_UNSUPPORTED";

    private PlanImportErrorCode() {
    }
}
