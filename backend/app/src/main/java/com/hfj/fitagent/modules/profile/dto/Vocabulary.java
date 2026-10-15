package com.hfj.fitagent.modules.profile.dto;

import java.util.Set;

/**
 * profile 域的枚举与代码词表，值域来源全部可追溯：
 * <ul>
 *   <li>{@link #EQUIPMENT}：docs/p1-plan-import.md §9 的器械代码表，与动作字典共用同一套；</li>
 *   <li>其余：V2/V3/V4 迁移里的 ENUM 定义，逐值对齐，不做同义扩展。</li>
 * </ul>
 * 契约层只接受大写字面量（docs/p1-database.md §5 补充规则 1），MySQL ENUM 本身大小写不敏感
 * 不足以成为放宽的理由——否则前后端对"大小写是否等价"的口径会分叉。
 */
public final class Vocabulary {

    public static final Set<String> EQUIPMENT = Set.of(
            "BARBELL", "DUMBBELL", "KETTLEBELL", "BENCH", "CABLE_MACHINE", "MACHINE",
            "TREADMILL", "BIKE", "ROWER", "ELLIPTICAL", "STAIR_CLIMBER", "JUMP_ROPE",
            "MAT", "BODYWEIGHT", "OUTDOOR", "BAND");

    public static final Set<String> TRAINING_LEVEL = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");

    public static final Set<String> SEX_FOR_EQUATION = Set.of("MALE", "FEMALE", "UNSPECIFIED");

    public static final Set<String> GOAL_TYPE = Set.of("FAT_LOSS", "MUSCLE_GAIN", "MAINTAIN", "GENERAL_FITNESS");

    public static final Set<String> GOAL_STATUS = Set.of("DRAFT", "ACTIVE", "ARCHIVED");

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ARCHIVED = "ARCHIVED";

    /** body_measurement.source 本轮恒为 MANUAL：导入进来的是计划，不是体重历史。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 两列 ENUM 的数据库默认值：请求省略时按同一口径落库，不做隐式猜测。 */
    public static final String DEFAULT_TRAINING_LEVEL = "BEGINNER";
    public static final String DEFAULT_SEX_FOR_EQUATION = "UNSPECIFIED";

    private Vocabulary() {
    }
}
