package com.hfj.fitagent.modules.metrics.vo;

import com.hfj.fitagent.modules.metrics.MetricsRules;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 统计出参集合（docs/stage2-design.md §6 的落地形状）。
 *
 * <p>三条形状上的硬规定：
 * <ol>
 *   <li>每个 {@link Metric} 都自带口径版本、参考计划版本、来源 session/entry、缺失项与 {@code dataRevision}，
 *       所以一个数字被截出来单独引用时，仍然能说清"它是按哪版口径、从哪些记录算出来的"。</li>
 *   <li>值拿不到就是 {@code value = null} + {@code unavailableReason}，不会出现 0% 或 100% 这种伪结论。</li>
 *   <li>计划侧与实际侧是两组独立字段（{@link PlannedSide} / {@link ActualSide}），
 *       结构上就不可能把"安排了"当成"练完了"。</li>
 * </ol>
 */
public final class MetricsVos {

    private MetricsVos() {
    }

    /** 指标共用的来源块：口径版本 + 所参考的计划版本 + 读数时的数据修订号。 */
    public record Context(String ruleVersion,
                          String referencePlanId,
                          Integer referenceRevisionNo,
                          String dataRevision) {

        public static Context of(String dataRevision) {
            return new Context(MetricsRules.RULE_VERSION, null, null, dataRevision);
        }

        /** 分组到某个计划版本后补上参考信息：不同版本的分母不混，来源也必须分开。 */
        public Context forPlan(String planId, Integer revisionNo) {
            return new Context(ruleVersion, planId, revisionNo, dataRevision);
        }
    }

    /**
     * 一个指标的完整自述。
     *
     * @param value             BigDecimal 而不是 double：口径要可复现，不能带二进制浮点的尾巴
     * @param unavailableReason 值为 null 时的原因码（见 {@link MetricsRules}），有值时为 null
     */
    public record Metric(String code,
                         BigDecimal value,
                         String unit,
                         String ruleVersion,
                         String referencePlanId,
                         Integer referenceRevisionNo,
                         List<String> sourceSessionIds,
                         List<String> sourceEntryIds,
                         List<String> missingFields,
                         String unavailableReason,
                         String dataRevision) {

        public static Metric of(Context ctx, String code, BigDecimal value, String unit,
                                List<String> sessionIds, List<String> entryIds, List<String> missing) {
            return new Metric(code, value, unit, ctx.ruleVersion(), ctx.referencePlanId(),
                    ctx.referenceRevisionNo(), copy(sessionIds), copy(entryIds), copy(missing), null,
                    ctx.dataRevision());
        }

        /** 直接计数（场次数、天数、组数）：0 是真实的零，与"无依据所以 null"不同义。 */
        public static Metric count(Context ctx, String code, long count, String unit,
                                   List<String> sessionIds, List<String> entryIds, List<String> missing) {
            return of(ctx, code, BigDecimal.valueOf(count), unit, sessionIds, entryIds, missing);
        }

        /** 无依据：value 必为 null，原因码必填——这是"不给 0%/100%"这条硬规则的唯一出口。 */
        public static Metric unavailable(Context ctx, String code, String unit, String reason,
                                         List<String> sessionIds, List<String> entryIds, List<String> missing) {
            return new Metric(code, null, unit, ctx.ruleVersion(), ctx.referencePlanId(),
                    ctx.referenceRevisionNo(), copy(sessionIds), copy(entryIds), copy(missing), reason,
                    ctx.dataRevision());
        }

        private static List<String> copy(List<String> values) {
            return values == null || values.isEmpty() ? List.of() : List.copyOf(values);
        }
    }

    // ------------------------------------------------------------------ 当天对比

    /**
     * 当天"计划安排 vs 已确认执行"。
     *
     * @param planVersions 按<b>计划版本</b>分组：一天引用了多个版本就分别成组，分母永不合并
     * @param selfInitiated 自主动作单列，不进完成度分母也不抵消缺口
     */
    public record DayComparison(LocalDate businessDate,
                                Integer isoWeekday,
                                String ruleVersion,
                                String dataRevision,
                                List<PlanVersionComparison> planVersions,
                                SelfInitiatedSummary selfInitiated,
                                DayRecordSummary recordSummary,
                                List<String> missingFields) {
    }

    /**
     * 一个计划版本在这一天的对比。
     *
     * @param scheduledForThisDate 该版本是否是<b>当前生效</b>计划在这一天（按 ISO 星期与起止区间）的安排；
     *                             为 false 表示只是历史场次当时参考的版本
     */
    public record PlanVersionComparison(String planId,
                                        String planDayId,
                                        String planTitle,
                                        Integer planRevisionNo,
                                        String planStatus,
                                        Boolean scheduledForThisDate,
                                        List<ItemComparison> items,
                                        List<Metric> metrics,
                                        List<String> sourceSessionIds,
                                        List<String> missingFields) {
    }

    /** 计划侧：全部来自所参考版本，实际值永不回填到这里。 */
    public record PlannedSide(Integer targetSets,
                              Integer repsMin,
                              Integer repsMax,
                              BigDecimal targetExternalLoadKg,
                              Integer targetDurationSeconds,
                              String unavailableReason) {
    }

    /**
     * 实际侧：只统计 {@code is_completed=1} 的组。
     *
     * @param cappedSets 先跨场次汇总再按计划组数封顶（超出部分进 {@code extraSets}）
     */
    public record ActualSide(Integer confirmedSets,
                             Integer cappedSets,
                             Integer extraSets,
                             Integer recordedDurationSeconds,
                             Integer cappedDurationSeconds,
                             String durationUnavailableReason) {
    }

    /** 次数是否在目标范围：单独一组，不参与"这一组有没有被确认"。 */
    public record RepsCheck(Boolean targetRangeKnown,
                            Integer confirmedSets,
                            Integer setsWithReps,
                            Integer setsInRepsRange,
                            Integer setsOutOfRepsRange,
                            Integer setsWithoutReps,
                            BigDecimal inRangePercent,
                            String unavailableReason) {
    }

    /**
     * 一个计划条目的对比行：{@code planned} 与 {@code actual} 是两组独立字段。
     *
     * <p>条目级也带自己的 {@code metrics}（{@code ITEM_} 前缀），因为"某个动作完成了几成"与
     * "这一版计划完成了几成"是两个各自成立的口径；分母为 0 的条目同样是 null + 原因码。
     */
    public record ItemComparison(String planItemId,
                                 String exerciseId,
                                 String exerciseCode,
                                 String exerciseName,
                                 Integer positionNo,
                                 PlannedSide planned,
                                 ActualSide actual,
                                 RepsCheck reps,
                                 List<Metric> metrics,
                                 List<String> sourceSessionIds,
                                 List<String> sourceEntryIds,
                                 List<String> missingFields) {
    }

    /** 自主动作的一行（plan_item_id 为 NULL：它没有分母，也就无从谈完成度）。 */
    public record SelfInitiatedEntry(String sessionId,
                                     String entryId,
                                     String exerciseId,
                                     String exerciseName,
                                     Integer positionNo,
                                     Integer confirmedSets,
                                     Integer recordedDurationSeconds,
                                     BigDecimal distanceMeters) {
    }

    public record SelfInitiatedSummary(List<SelfInitiatedEntry> entries,
                                       List<Metric> metrics,
                                       List<String> missingFields) {
    }

    /**
     * 当天记录摘要。
     *
     * <p>{@code sessionElapsedSecondsTotal}（场次历时）与 {@code recordedMovementSecondsTotal}（动作时长）
     * 是两个各自成立的量，<b>不相加</b>：前者含组间休息与准备时间，相加会得出一个谁都没记过的"总活动时长"。
     */
    public record DayRecordSummary(Integer completedSessionCount,
                                   List<String> sessionIds,
                                   Integer recordedEntryCount,
                                   Integer confirmedSetCount,
                                   Integer sessionElapsedSecondsTotal,
                                   Integer recordedMovementSecondsTotal,
                                   Instant firstStartedAt,
                                   Instant lastEndedAt,
                                   List<Metric> metrics,
                                   List<String> missingFields) {
    }

    // ------------------------------------------------------------------ 本周统计

    /** 周里某一天：只给计数与时长，不给百分比（周层面平均日百分比是假口径）。 */
    public record WeekDayRow(LocalDate businessDate,
                             Integer sessionCount,
                             Integer confirmedSetCount,
                             Integer recordedMovementSeconds,
                             String movementUnavailableReason,
                             Integer sessionElapsedSeconds) {
    }

    public record WeekSummary(LocalDate weekStartDate,
                              LocalDate weekEndDate,
                              String ruleVersion,
                              String dataRevision,
                              List<Metric> metrics,
                              List<WeekDayRow> days,
                              List<String> missingFields) {
    }
}
