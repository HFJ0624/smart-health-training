package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.modules.training.dto.PlanDraftSpec;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.modules.training.dto.PlanUpsertRequest;
import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;
import com.hfj.fitagent.modules.training.entity.GoalSnapshot;
import com.hfj.fitagent.modules.training.entity.TrainingPlanDayEntity;
import com.hfj.fitagent.modules.training.entity.TrainingPlanEntity;
import com.hfj.fitagent.modules.training.entity.TrainingPlanItemEntity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试夹具：动作字典、计划版本头/日/条目、聚合草案的构造器。
 *
 * <p>集中一处是为了让每个用例只剩下"这一条规则相关的差异"——
 * 断言读起来像规则本身，而不是一堆 setXxx。
 *
 * <p>字典数据取自 docs/p1-plan-import.md §9 的种子清单（只取用例需要的条目，类别必须与种子一致：
 * 类别决定校验矩阵，抄错类别等于测了一条不存在的规则）。
 */
final class TrainingFixtures {

    static final long USER_ID = 7L;
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final Instant NOW = Instant.parse("2026-10-13T07:20:00Z");
    static final LocalDate STARTS_ON = LocalDate.of(2026, 10, 12);
    static final String GROUP = "0f6f3a9c-2f1b-4a55-9c1d-7e2b0a4f6c11";

    /** §9 种子里的力量动作。 */
    static final String SQUAT = "BARBELL_BACK_SQUAT";
    static final String ROWING = "ROWING";
    static final String CAT_COW = "CAT_COW";
    static final String PUSH_UP = "PUSH_UP";

    private TrainingFixtures() {
    }

    static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneId.of("UTC"));
    }

    static AppClock clock() {
        return new AppClock(fixedClock());
    }

    // ==================== 字典 ====================

    static ExerciseReferenceEntity exercise(long id, String code, String category) {
        return exercise(id, code, category, "ACTIVE");
    }

    static ExerciseReferenceEntity exercise(long id, String code, String category, String status) {
        ExerciseReferenceEntity entity = new ExerciseReferenceEntity();
        entity.setId(id);
        entity.setExerciseCode(code);
        entity.setName("动作-" + code);
        entity.setCategory(category);
        entity.setStatus(status);
        entity.setMuscleGroups(List.of("股四头肌"));
        entity.setEquipment(List.of("BARBELL"));
        return entity;
    }

    /** 代码 → 字典行，键是规范化后的大写代码（与 ExerciseDictionary 的返回口径一致）。 */
    static Map<String, ExerciseReferenceEntity> dictionaryOf(ExerciseReferenceEntity... rows) {
        Map<String, ExerciseReferenceEntity> byCode = new LinkedHashMap<>();
        for (ExerciseReferenceEntity row : rows) {
            byCode.put(row.getExerciseCode(), row);
        }
        return byCode;
    }

    /** id → 字典行，供读取路径的 mock 使用。 */
    static Map<Long, ExerciseReferenceEntity> dictionaryById(ExerciseReferenceEntity... rows) {
        Map<Long, ExerciseReferenceEntity> byId = new LinkedHashMap<>();
        for (ExerciseReferenceEntity row : rows) {
            byId.put(row.getId(), row);
        }
        return byId;
    }

    static ExerciseReferenceEntity squat() {
        return exercise(1L, SQUAT, "STRENGTH");
    }

    static ExerciseReferenceEntity rowing() {
        return exercise(2L, ROWING, "CARDIO");
    }

    static ExerciseReferenceEntity catCow() {
        return exercise(3L, CAT_COW, "MOBILITY");
    }

    static ExerciseReferenceEntity pushUp() {
        return exercise(4L, PUSH_UP, "STRENGTH");
    }

    // ==================== 实体 ====================

    static TrainingPlanEntity header(long id, PlanStatus status, int lockVersion) {
        TrainingPlanEntity header = new TrainingPlanEntity();
        header.setId(id);
        header.setUserId(USER_ID);
        header.setPlanGroupUuid(GROUP);
        header.setRevisionNo(1);
        header.setTitle("综合周计划");
        header.setSource(TrainingPlanService.SOURCE_MANUAL);
        header.setStatus(status.code());
        header.setGoalSnapshot(snapshot());
        header.setStartsOn(STARTS_ON);
        header.setLockVersion(lockVersion);
        header.setCreatedAt(NOW);
        header.setUpdatedAt(NOW);
        return header;
    }

    static GoalSnapshot snapshot() {
        return new GoalSnapshot(GoalSnapshot.SCHEMA_VERSION, NOW, null, null, null, null,
                new BigDecimal("175.00"), "BEGINNER", List.of("BARBELL"),
                List.of("goalId", "goalType", "targetWeightKg", "weeklySessionsTarget"));
    }

    static TrainingPlanDayEntity day(long id, long planId, int isoWeekday, boolean restDay) {
        TrainingPlanDayEntity day = new TrainingPlanDayEntity();
        day.setId(id);
        day.setUserId(USER_ID);
        day.setPlanId(planId);
        day.setIsoWeekday(isoWeekday);
        day.setTitle("第" + isoWeekday + "天");
        day.setIsRestDay(restDay);
        return day;
    }

    static TrainingPlanItemEntity item(long id, long dayId, long exerciseId, int positionNo) {
        TrainingPlanItemEntity item = new TrainingPlanItemEntity();
        item.setId(id);
        item.setUserId(USER_ID);
        item.setPlanDayId(dayId);
        item.setExerciseId(exerciseId);
        item.setPositionNo(positionNo);
        item.setTargetSets(3);
        item.setRepsMin(8);
        item.setRepsMax(10);
        item.setTargetExternalLoadKg(new BigDecimal("60.00"));
        return item;
    }

    // ==================== 聚合草案 ====================

    /** 合法的 STRENGTH 条目：组数 + 次数 + 负重 + 休息。 */
    static PlanDraftSpec.ItemSpec strength(String code) {
        return new PlanDraftSpec.ItemSpec(code, null, 3, 8, 10, new BigDecimal("60.00"), null, 90, null);
    }

    /** 合法的 CARDIO 条目：只有时长。 */
    static PlanDraftSpec.ItemSpec cardio(String code) {
        return new PlanDraftSpec.ItemSpec(code, null, null, null, null, null, 1200, null, null);
    }

    /** 合法的 MOBILITY 条目：按时长记，不记负重。 */
    static PlanDraftSpec.ItemSpec mobility(String code) {
        return new PlanDraftSpec.ItemSpec(code, null, null, null, null, null, 90, null, null);
    }

    static PlanDraftSpec.ItemSpec item(String code, Integer positionNo, Integer targetSets, Integer repsMin,
                                      Integer repsMax, BigDecimal load, Integer duration, Integer rest) {
        return new PlanDraftSpec.ItemSpec(code, positionNo, targetSets, repsMin, repsMax, load, duration, rest, null);
    }

    static PlanDraftSpec.DaySpec trainingDay(int isoWeekday, PlanDraftSpec.ItemSpec... items) {
        return new PlanDraftSpec.DaySpec(isoWeekday, "训练日" + isoWeekday, false, null, List.of(items));
    }

    static PlanDraftSpec.DaySpec restDay(int isoWeekday) {
        return new PlanDraftSpec.DaySpec(isoWeekday, "休息日", true, null, List.of());
    }

    static PlanDraftSpec.DaySpec day(Integer isoWeekday, Boolean isRestDay, List<PlanDraftSpec.ItemSpec> items) {
        return new PlanDraftSpec.DaySpec(isoWeekday, null, isRestDay, null, items);
    }

    static PlanDraftSpec spec(PlanDraftSpec.DaySpec... days) {
        return new PlanDraftSpec("综合周计划", STARTS_ON, null, null, List.of(days));
    }

    static PlanDraftSpec spec(LocalDate endsOn, List<PlanDraftSpec.DaySpec> days) {
        return new PlanDraftSpec("综合周计划", STARTS_ON, endsOn, null, days);
    }

    // ==================== 手工提交 DTO ====================

    static PlanUpsertRequest upsert(Integer lockVersion, PlanUpsertRequest.Day... days) {
        return new PlanUpsertRequest("综合周计划", STARTS_ON, null, List.of(days), lockVersion);
    }

    static PlanUpsertRequest.Day dayDto(int isoWeekday, boolean restDay, PlanUpsertRequest.Item... items) {
        return new PlanUpsertRequest.Day(isoWeekday, "训练日" + isoWeekday, restDay, null, List.of(items));
    }

    static PlanUpsertRequest.Item strengthDto(String code) {
        return new PlanUpsertRequest.Item(code, null, 3, 8, 10, new BigDecimal("60.00"), null, 90, null);
    }

    /** 一条已经过校验的合法聚合：供 mock 校验器返回，让状态机用例只测状态机。 */
    static PlanValidationOutcome validOutcome() {
        PlanValidationOutcome.ResolvedItem resolved = new PlanValidationOutcome.ResolvedItem(
                1, squat(), 3, 8, 10, new BigDecimal("60.00"), null, 90, null);
        List<PlanValidationOutcome.ResolvedDay> days = new ArrayList<>();
        days.add(new PlanValidationOutcome.ResolvedDay(1, "训练日1", false, null, List.of(resolved)));
        days.add(new PlanValidationOutcome.ResolvedDay(3, "休息日", true, null, List.of()));
        return new PlanValidationOutcome(List.of(), List.of(), days);
    }
}
