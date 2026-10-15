package com.hfj.fitagent.modules.coach.tools;

/**
 * 本轮注册的工具名与相关字面量。
 *
 * <p>名字是<b>对外契约</b>的一部分：小程序 {@code types/coach.ts} 的 {@code COACH_TOOL_LABELS}
 * 按这三个字面值匹配，§11.1 的 {@code availableTools} 也按同一组字符串返回，所以只能增删工具、
 * 不能改名。命名也符合 {@code ToolDefinition.NAME_PATTERN}（{@code [a-zA-Z0-9_-]}）。
 */
public final class CoachToolNames {

    public static final String GET_PROFILE = "get_profile";
    public static final String GET_TRAINING_PLAN = "get_training_plan";
    public static final String GET_WEIGHT_TREND = "get_weight_trend";

    /** 本轮白名单全集：顺序固定，用于 estimate 的 availableTools 与 AgentContext.allowedTools。 */
    public static final java.util.Set<String> ALL = java.util.Set.of(GET_PROFILE, GET_TRAINING_PLAN,
            GET_WEIGHT_TREND);

    /** 证据绑定用的表名字面值（与 V2–V8 的表名逐字一致，不引入第二种拼法）。 */
    public static final String TABLE_USER_PROFILE = "user_profile";
    public static final String TABLE_USER_GOAL = "user_goal";
    public static final String TABLE_TRAINING_PLAN = "training_plan";
    public static final String TABLE_TRAINING_PLAN_DAY = "training_plan_day";
    public static final String TABLE_BODY_MEASUREMENT = "body_measurement";

    /**
     * 本轮没有来源的口径标识（AGENTS.md：宁缺毋伪）。
     *
     * <p>{@code Evidence.ruleVersion} 需要一个版本字符串，而 baseline §3.1 确认
     * {@code calculation_rule} 表与任何规则版本服务都不存在，因此这里给的是<b>声明式的"无规则"</b>，
     * 而不是编造一个 {@code v1} 让事实卡片看起来像出自某个规则库。
     */
    public static final String RULE_NONE = "NO_RULE_VERIFIED";

    private CoachToolNames() {
    }
}
