package com.hfj.fitagent.modules.workout.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 训练执行的出参集合（与 {@code WorkoutRequests} 同一套"一棵树放一处"的取舍）。
 *
 * <p>计划参考与实际记录**永远分开展示**：{@code planTarget*} 是参考值（可能为 null=自主动作），
 * {@code completedSets}/{@code actual*} 是用户确认过的实际值。缺失就是 null，
 * 前端不得用计划值补成实际值，服务端也不会。
 */
public final class WorkoutVos {

    private WorkoutVos() {
    }

    public record SetVo(String id,
                        Integer setNo,
                        Integer reps,
                        BigDecimal externalLoadKg,
                        Integer durationSeconds,
                        Boolean isCompleted,
                        String notes) {
    }

    /**
     * @param exerciseName    字典里的动作名；字典被改过也按当时的 id 现取，不在场次里冗余存名
     * @param planTargetSets  参考条目的计划组数；null 表示自主动作或计划未给该值
     * @param completedSets   该条目下 is_completed=1 的组数（用户确认过的）
     * @param recordedSets    该条目下已记录的组数（不管有没有确认完成）
     */
    public record EntryVo(String id,
                          Integer positionNo,
                          String exerciseId,
                          String exerciseCode,
                          String exerciseName,
                          String category,
                          String planItemId,
                          Integer planTargetSets,
                          Integer planRepsMin,
                          Integer planRepsMax,
                          BigDecimal planTargetExternalLoadKg,
                          Integer planTargetDurationSeconds,
                          Integer durationSeconds,
                          BigDecimal distanceMeters,
                          Integer completedSets,
                          Integer recordedSets,
                          String notes,
                          List<SetVo> sets) {
    }

    /** 列表项：只给读一眼需要的东西，明细走 detail。 */
    public record SessionSummaryVo(String id,
                                   LocalDate businessDate,
                                   String status,
                                   String planId,
                                   String planDayId,
                                   Integer planRevisionNo,
                                   Instant startedAt,
                                   Instant endedAt,
                                   Integer durationSeconds,
                                   Integer entryCount,
                                   Integer completedSetCount,
                                   String notes,
                                   Integer lockVersion) {
    }

    public record SessionDetailVo(String id,
                                  String clientRecordUuid,
                                  LocalDate businessDate,
                                  String timezone,
                                  String status,
                                  String planId,
                                  String planTitle,
                                  Integer planRevisionNo,
                                  String planDayId,
                                  Integer planDayIsoWeekday,
                                  Instant startedAt,
                                  Instant endedAt,
                                  Integer durationSeconds,
                                  String notes,
                                  Integer lockVersion,
                                  List<EntryVo> entries) {
    }

    /** 「开始/恢复」的返回：让前端立刻知道自己是新建还是恢复了已有草稿。 */
    public record StartResultVo(SessionDetailVo session, Boolean resumedExisting) {
    }
}
