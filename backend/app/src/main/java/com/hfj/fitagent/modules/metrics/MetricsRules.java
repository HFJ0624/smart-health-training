package com.hfj.fitagent.modules.metrics;

/**
 * 统计口径的唯一常量表（docs/stage2-design.md §6）。
 *
 * <p>指标码、单位与"拿不到值时的原因码"都放在这里而不是散在算法里：口径一旦改动，
 * 前端与 Agent 工具按 code 分支的契约就跟着变，必须有一个可以逐条审查的地方。
 * {@link #RULE_VERSION} 会随每个指标一起返回，历史结论因此能被追溯是"按哪一版口径算的"。
 */
public final class MetricsRules {

    /** 口径版本：封顶算法、原因码语义任何一处改动都要升这个值。 */
    public static final String RULE_VERSION = "workout-completion-v1";

    // ---- 指标码 ----
    /** 组数完成度（封顶后的已记录组数 / 计划目标组数），只做已确认记录之间的事 */
    public static final String SET_COMPLETION_PCT = "SET_COMPLETION_PCT";
    /** 封顶后计入完成度的组数（同一条目先跨场次汇总再 min） */
    public static final String CONFIRMED_SETS_CAPPED = "CONFIRMED_SETS_CAPPED";
    /** 该组目标动作的计划组数合计（分母） */
    public static final String PLANNED_TARGET_SETS = "PLANNED_TARGET_SETS";
    /** 超出计划组数的已确认组，单列，不抵消其它条目的缺口 */
    public static final String EXTRA_SETS = "EXTRA_SETS";
    /** 实际动作时长合计（逐条目按目标封顶后汇总），与组数分开 */
    public static final String CAPPED_MOVEMENT_SECONDS = "CAPPED_MOVEMENT_SECONDS";
    /** 已记录到的实际动作时长合计（不封顶），与场次历时分开 */
    public static final String RECORDED_MOVEMENT_SECONDS = "RECORDED_MOVEMENT_SECONDS";
    /** 已确认组的次数落在计划次数区间的比例，单列，不参与"组是否确认" */
    public static final String REPS_IN_TARGET_RANGE_PCT = "REPS_IN_TARGET_RANGE_PCT";
    /** 自主动作（不参考任何计划条目）条目数 */
    public static final String SELF_INITIATED_ENTRY_COUNT = "SELF_INITIATED_ENTRY_COUNT";
    /** 自主动作里已确认完成的组数 */
    public static final String SELF_INITIATED_CONFIRMED_SETS = "SELF_INITIATED_CONFIRMED_SETS";
    /** 自主动作记录到的实际时长 */
    public static final String SELF_INITIATED_MOVEMENT_SECONDS = "SELF_INITIATED_MOVEMENT_SECONDS";
    /** 当天已确认场次数（直接计数，0 是真实的零） */
    public static final String COMPLETED_SESSION_COUNT = "COMPLETED_SESSION_COUNT";
    /** 本周出现过已确认记录的不同业务日期数 */
    public static final String DISTINCT_TRAINING_DAY_COUNT = "DISTINCT_TRAINING_DAY_COUNT";
    /** 场次历时合计（ended_at - started_at），与动作时长分列，绝不相加成"总活动时长" */
    public static final String SESSION_ELAPSED_SECONDS = "SESSION_ELAPSED_SECONDS";
    /** 本周"证据完整"的场次动作时长合计 */
    public static final String EVIDENCED_MOVEMENT_SECONDS = "EVIDENCED_MOVEMENT_SECONDS";
    /** 当天/本周已确认完成的组数合计（直接计数） */
    public static final String CONFIRMED_SETS_TOTAL = "CONFIRMED_SETS_TOTAL";

    // ---- 条目级指标码（与分组级口径同源，只是分母只属于这一个计划条目）----
    public static final String ITEM_CONFIRMED_SETS = "ITEM_CONFIRMED_SETS";
    public static final String ITEM_CONFIRMED_SETS_CAPPED = "ITEM_CONFIRMED_SETS_CAPPED";
    public static final String ITEM_EXTRA_SETS = "ITEM_EXTRA_SETS";
    public static final String ITEM_PLANNED_TARGET_SETS = "ITEM_PLANNED_TARGET_SETS";
    public static final String ITEM_SET_COMPLETION_PCT = "ITEM_SET_COMPLETION_PCT";
    public static final String ITEM_RECORDED_MOVEMENT_SECONDS = "ITEM_RECORDED_MOVEMENT_SECONDS";
    public static final String ITEM_CAPPED_MOVEMENT_SECONDS = "ITEM_CAPPED_MOVEMENT_SECONDS";
    public static final String ITEM_REPS_IN_TARGET_RANGE_PCT = "ITEM_REPS_IN_TARGET_RANGE_PCT";

    // ---- 单位 ----
    public static final String UNIT_PERCENT = "PERCENT";
    public static final String UNIT_SETS = "SETS";
    public static final String UNIT_SECONDS = "SECONDS";
    public static final String UNIT_SESSIONS = "SESSIONS";
    public static final String UNIT_DAYS = "DAYS";
    public static final String UNIT_ENTRIES = "ENTRIES";

    // ---- 原因码：值必须为 null 时给出，绝不给 0% 或 100% ----
    /** 分母为 0：计划没给这一类的目标（例如有氧条目没有组数目标） */
    public static final String NO_TARGET = "NO_TARGET";
    /** 当天/本周没有任何已确认场次 */
    public static final String NO_CONFIRMED_SESSION = "NO_CONFIRMED_SESSION";
    /** 该日期在生效计划里没有排程（休息日或计划区间外） */
    public static final String NO_PLAN_SCHEDULED = "NO_PLAN_SCHEDULED";
    /** 计划条目本身没有任何可参考的目标值 */
    public static final String NO_PLAN_ITEMS = "NO_PLAN_ITEMS";
    /** 实际时长没记录（未知≠0） */
    public static final String DURATION_NOT_RECORDED = "DURATION_NOT_RECORDED";
    /** 计划没给目标时长，无法封顶，因此不进封顶合计 */
    public static final String NO_PLAN_DURATION_TARGET = "NO_PLAN_DURATION_TARGET";
    /** 没有已确认组记录了次数 */
    public static final String NO_REPS_RECORDED = "NO_REPS_RECORDED";
    /** 计划没给次数区间，无法判定是否在目标范围 */
    public static final String NO_REPS_TARGET = "NO_REPS_TARGET";
    /** 参考的计划版本读不到（计划行按不变式不该消失，出现即如实标注而不是猜一个分母） */
    public static final String PLAN_VERSION_UNAVAILABLE = "PLAN_VERSION_UNAVAILABLE";
    /** 条目引用了计划条目，但该条目不在所参考的计划日里（历史脏数据护栏） */
    public static final String PLAN_ITEM_NOT_IN_REFERENCED_DAY = "PLAN_ITEM_NOT_IN_REFERENCED_DAY";
    /** 场次没有参考计划却带着 planItemId：按自主动作处理并如实标注，不给它凭空造分母 */
    public static final String PLAN_ITEM_WITHOUT_PLAN_REFERENCE = "PLAN_ITEM_WITHOUT_PLAN_REFERENCE";
    /** 场次的 started_at/ended_at 不全，历时算不出来（不是 0 秒） */
    public static final String SESSION_ELAPSED_UNAVAILABLE = "SESSION_ELAPSED_UNAVAILABLE";
    /** 场次快照的版本号与读到的计划行不一致：值仍按计划行算，但必须标出来 */
    public static final String PLAN_REVISION_SNAPSHOT_MISMATCH = "PLAN_REVISION_SNAPSHOT_MISMATCH";

    /** 百分比统一 2 位小数 HALF_UP：口径可复现，且不会出现浮点尾巴。 */
    public static final int PERCENT_SCALE = 2;

    private MetricsRules() {
    }
}
