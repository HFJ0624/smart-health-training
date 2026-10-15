package com.hfj.fitagent.modules.training.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code training_plan.goal_snapshot} 的对外视图。
 *
 * <p>这是"建立计划当时的背景快照"，不是当前目标：后续目标变更不回写历史计划版本，
 * 所以计划详情里显示的永远是该版本落地时的依据（docs/p1-database.md §4.4）。
 *
 * <p>{@code absentFields} 是这份快照可信度的关键：档案缺失或没有 ACTIVE 目标时，
 * 相关键名出现在这里并且值为 null，而<b>不是</b>被填一个看起来合理的默认值。
 * 页面据此区分"没设定"与"设定为 0"。
 *
 * <p>刻意不复用 {@code entity.GoalSnapshot}：数据库侧的 JSON 值对象不外泄为响应
 * （docs/p1-database.md §6"数据库 Entity 不作为接口响应"）。
 */
public record GoalSnapshotVo(String schemaVersion,
                            Instant capturedAt,
                            String goalId,
                            String goalType,
                            BigDecimal targetWeightKg,
                            Integer weeklySessionsTarget,
                            BigDecimal heightCm,
                            String trainingLevel,
                            List<String> equipment,
                            List<String> absentFields) {
}
