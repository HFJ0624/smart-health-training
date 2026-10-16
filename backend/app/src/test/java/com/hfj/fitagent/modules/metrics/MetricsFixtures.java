package com.hfj.fitagent.modules.metrics;

import com.hfj.fitagent.modules.training.vo.PlanDayVo;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.workout.WorkoutSessionStatus;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
import com.hfj.fitagent.modules.workout.vo.WorkoutVos;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * metrics 测试夹具：只造 Service 的出参 VO，不碰数据库、不碰 Mapper。
 *
 * <p>record 的字段顺序很长且抄错就静默走偏（例如把"计划目标组数"写到实际位），
 * 所以集中在这里造一次，用例里只剩下与该规则相关的那点差异。
 */
final class MetricsFixtures {

    static final long USER = 7L;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2026-10-12 是周一，2026-10-14 是周三（ISO 星期 3）：与 handoff 里那批真库验证同一天，方便对照。 */
    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);

    private static final AtomicInteger SET_SEQ = new AtomicInteger();

    private MetricsFixtures() {
    }

    // ==================== workout 侧 ====================

    static WorkoutSessionEntity row(long id, LocalDate businessDate, WorkoutSessionStatus status) {
        WorkoutSessionEntity entity = new WorkoutSessionEntity();
        entity.setId(id);
        entity.setUserId(USER);
        entity.setBusinessDate(businessDate);
        entity.setTimezone(ZONE.getId());
        entity.setStatus(status);
        entity.setStartedAt(Instant.parse("2026-10-14T10:00:00Z"));
        entity.setEndedAt(status == WorkoutSessionStatus.COMPLETED ? Instant.parse("2026-10-14T11:00:00Z") : null);
        return entity;
    }

    static WorkoutSessionEntity completedRow(long id, LocalDate businessDate) {
        return row(id, businessDate, WorkoutSessionStatus.COMPLETED);
    }

    /** 已确认场次：planId/planDayId 成对给出，历时按 elapsedSeconds 表达。 */
    static WorkoutVos.SessionDetailVo session(String id, LocalDate businessDate, String planId, String planDayId,
                                              Integer planRevisionNo, Integer elapsedSeconds,
                                              WorkoutVos.EntryVo... entries) {
        return sessionWithStatus(id, businessDate, "COMPLETED", planId, planDayId, planRevisionNo,
                elapsedSeconds, entries);
    }

    /** 带状态明细的场次：用于"实体说已确认、明细却是草稿"这类脏数据用例。 */
    static WorkoutVos.SessionDetailVo sessionWithStatus(String id, LocalDate businessDate, String status,
                                                        String planId, String planDayId, Integer planRevisionNo,
                                                        Integer elapsedSeconds, WorkoutVos.EntryVo... entries) {
        Instant started = Instant.parse("2026-10-14T10:00:00Z");
        Instant ended = elapsedSeconds == null ? null : started.plusSeconds(elapsedSeconds);
        return new WorkoutVos.SessionDetailVo(id, "uuid-" + id, businessDate, ZONE.getId(), status,
                planId, "计划 " + planId, planRevisionNo, planDayId,
                planDayId == null ? null : businessDate.getDayOfWeek().getValue(),
                started, ended, elapsedSeconds, null, 2, List.of(entries));
    }

    /**
     * 一个实际动作条目。
     *
     * <p>{@code completedSets} 由 sets 里 {@code isCompleted=true} 的条数推出来——
     * 夹具自己数一遍，避免"手写一个和 sets 不一致的组数"这种假前置条件。
     */
    static WorkoutVos.EntryVo entry(String id, int positionNo, String exerciseId, String exerciseName,
                                    String planItemId, Integer planTargetSets, Integer planRepsMin,
                                    Integer planRepsMax, Integer planTargetDurationSeconds,
                                    Integer actualDurationSeconds, WorkoutVos.SetVo... sets) {
        List<WorkoutVos.SetVo> setList = List.of(sets);
        int completed = (int) setList.stream().filter(s -> Boolean.TRUE.equals(s.isCompleted())).count();
        return new WorkoutVos.EntryVo(id, positionNo, exerciseId, "CODE_" + exerciseId, exerciseName, "STRENGTH",
                planItemId, planTargetSets, planRepsMin, planRepsMax, null, planTargetDurationSeconds,
                actualDurationSeconds, null, completed, setList.size(), null, setList);
    }

    static WorkoutVos.SetVo confirmedSet(Integer reps) {
        return new WorkoutVos.SetVo("set-" + SET_SEQ.incrementAndGet(), 1, reps, null, null, true, null);
    }

    static WorkoutVos.SetVo confirmedSetWithDuration(int seconds) {
        return new WorkoutVos.SetVo("set-" + SET_SEQ.incrementAndGet(), 1, null, null, seconds, true, null);
    }

    static WorkoutVos.SetVo unconfirmedSet(Integer reps) {
        return new WorkoutVos.SetVo("set-" + SET_SEQ.incrementAndGet(), 1, reps, null, null, false, null);
    }

    /** n 个已确认组（次数未知）：夹具用数量表达"确认了几组"，用例里就不必再写循环。 */
    static WorkoutVos.SetVo[] confirmed(int count) {
        WorkoutVos.SetVo[] sets = new WorkoutVos.SetVo[count];
        for (int i = 0; i < count; i++) {
            sets[i] = confirmedSet(null);
        }
        return sets;
    }

    static WorkoutVos.SetVo[] confirmedWithReps(Integer... reps) {
        WorkoutVos.SetVo[] sets = new WorkoutVos.SetVo[reps.length];
        for (int i = 0; i < reps.length; i++) {
            sets[i] = confirmedSet(reps[i]);
        }
        return sets;
    }

    static WorkoutVos.SetVo[] notConfirmed(int count) {
        WorkoutVos.SetVo[] sets = new WorkoutVos.SetVo[count];
        for (int i = 0; i < count; i++) {
            sets[i] = unconfirmedSet(null);
        }
        return sets;
    }

    // ==================== training 侧 ====================

    static PlanItemVo planItem(String id, String exerciseId, String name, int positionNo, Integer targetSets,
                               Integer repsMin, Integer repsMax, Integer targetDurationSeconds) {
        return new PlanItemVo(id, exerciseId, "CODE_" + exerciseId, name, targetDurationSeconds == null
                ? "STRENGTH" : "CARDIO", positionNo, targetSets, repsMin, repsMax,
                targetSets == null ? null : new BigDecimal("60.00"), targetDurationSeconds,
                targetSets == null ? null : 90, null);
    }

    static PlanDayVo planDay(String id, String planId, int isoWeekday, PlanItemVo... items) {
        return new PlanDayVo(id, planId, isoWeekday, "第 " + isoWeekday + " 天", false, null, List.of(items));
    }

    static PlanDetailVo plan(String id, Integer revisionNo, String status, LocalDate startsOn, LocalDate endsOn,
                             PlanDayVo... days) {
        return new PlanDetailVo(id, Long.toString(USER), "0f6f3a9c-2f1b-4a55-9c1d-7e2b0a4f6c11", revisionNo, null,
                "计划 " + id, "MANUAL", status, startsOn, endsOn, Instant.parse("2026-10-11T00:00:00Z"), 1,
                null, null, null, List.of(days));
    }
}
