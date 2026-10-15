package com.hfj.fitagent.modules.training.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 计划建立时的目标/器械/限制快照，写入 training_plan.goal_snapshot（JSON NOT NULL）。
 *
 * 为什么本轮就要定义它：goal_snapshot 是 NOT NULL，手工与导入计划也必须写；
 * 而设计约定"NULL 表示未知，不当 0"，所以缺失项要显式进 absentFields 而不是补造数值。
 * 快照还带 schema_version 与体积校验，对应设计包"JSON 必须校验版本和体积"。
 *
 * 注意：这是"建立计划当时的背景快照"，不是当前目标。后续目标变更不回写历史计划。
 */
public record GoalSnapshot(
        String schemaVersion,
        Instant capturedAt,
        String goalId,
        String goalType,
        BigDecimal targetWeightKg,
        Integer weeklySessionsTarget,
        BigDecimal heightCm,
        String trainingLevel,
        List<String> equipment,
        List<String> absentFields) {

    public static final String SCHEMA_VERSION = "fitagent.goal_snapshot/1";

    /** 无 ACTIVE 目标时的快照：相关键进 absentFields，值保持 null，不补造。 */
    public static GoalSnapshot withoutGoal(Instant capturedAt, BigDecimal heightCm, String trainingLevel, List<String> equipment) {
        return new GoalSnapshot(SCHEMA_VERSION, capturedAt, null, null, null, null,
                heightCm, trainingLevel, equipment,
                List.of("goalId", "goalType", "targetWeightKg", "weeklySessionsTarget"));
    }
}
