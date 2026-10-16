package com.hfj.fitagent.modules.metrics;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.metrics.support.MetricsDataRevisionReader;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import com.hfj.fitagent.modules.training.TrainingPlanService;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.workout.WorkoutSessionStatus;
import com.hfj.fitagent.modules.workout.WorkoutService;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
import com.hfj.fitagent.modules.workout.vo.WorkoutVos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统计口径的单元测试（docs/stage2-design.md §6）：用 Mockito 打桩 {@code WorkoutService} 与
 * {@code TrainingPlanService}，不起 Spring 上下文、不连数据库（真库验收另有安排）。
 *
 * <p>这批用例的意义不是覆盖率，而是把四条最容易静默出错的规则钉住：封顶的时机（先跨场次汇总）、
 * 分母的分组（按计划版本）、未知的表达（null + 原因码而不是 0），以及草稿/已丢弃永不参与统计。
 * 这四条只要破一条，页面与 Agent 工具会一起给出同一个假数。
 */
class TrainingMetricsServiceTest {

    private static final long USER = MetricsFixtures.USER;
    private static final LocalDate WEDNESDAY = MetricsFixtures.WEDNESDAY;
    private static final LocalDate MONDAY = MetricsFixtures.MONDAY;
    private static final LocalDate SUNDAY = MONDAY.plusDays(6);

    private WorkoutService workoutService;
    private TrainingPlanService planService;
    private MetricsDataRevisionReader revisionReader;
    private TrainingMetricsService service;

    @BeforeEach
    void setUp() {
        workoutService = mock(WorkoutService.class);
        planService = mock(TrainingPlanService.class);
        revisionReader = mock(MetricsDataRevisionReader.class);
        service = new TrainingMetricsService(workoutService, planService, revisionReader);
        when(revisionReader.currentRevisionText(USER)).thenReturn("42");
    }

    // ================================================================== 必过示例

    @Test
    @DisplayName("必过示例：计划 A/B 各 3 组，实际确认 A=5、B=0 → 已记录组数完成度 50%、额外 2 组")
    void recordedSetCompletionExample_fiveSetsOnA_noneOnB_isFiftyPercentWithTwoExtraSets() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE",
                squat("31", 3), bench("32", 3)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 3600,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, 8, 12, null, null,
                        MetricsFixtures.confirmed(5)),
                // B 记了两组但一组都没确认：已记录口径下它就是 0
                MetricsFixtures.entry("62", 2, "42", "卧推", "32", 3, 8, 12, null, null,
                        MetricsFixtures.notConfirmed(2))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        MetricsVos.PlanVersionComparison group = group(result, "11");

        assertThat(metric(group.metrics(), MetricsRules.PLANNED_TARGET_SETS).value()).isEqualByComparingTo("6");
        assertThat(metric(group.metrics(), MetricsRules.CONFIRMED_SETS_CAPPED).value()).isEqualByComparingTo("3");
        MetricsVos.Metric completion = metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT);
        assertThat(completion.value()).isNotNull();
        assertThat(completion.value().toPlainString()).isEqualTo("50.00");
        assertThat(metric(group.metrics(), MetricsRules.EXTRA_SETS).value()).isEqualByComparingTo("2");

        // 超出的是 A，缺口在 B：额外组没有去抵消 B 的 3 组——这正是 50% 而不是 33% 或 100% 的原因
        MetricsVos.ItemComparison squat = item(group, "31");
        assertThat(squat.planned().targetSets()).isEqualTo(3);
        assertThat(squat.actual().confirmedSets()).isEqualTo(5);
        assertThat(squat.actual().cappedSets()).isEqualTo(3);
        assertThat(squat.actual().extraSets()).isEqualTo(2);
        MetricsVos.ItemComparison bench = item(group, "32");
        assertThat(bench.planned().targetSets()).isEqualTo(3);
        assertThat(bench.actual().confirmedSets()).isZero();
        assertThat(bench.actual().cappedSets()).isZero();
        assertThat(bench.actual().extraSets()).isZero();
    }

    @Test
    @DisplayName("同一条目跨场次先汇总再封顶：两场各确认 2 组、计划 3 组 → 计 3 组、额外 1 组")
    void setsAreSummedAcrossSessionsBeforeCapping() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3)));
        givenSessions(
                session("101", WEDNESDAY, "11", "21", 3, 600,
                        MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, null, null,
                                MetricsFixtures.confirmed(2))),
                session("102", WEDNESDAY, "11", "21", 3, 600,
                        MetricsFixtures.entry("62", 1, "41", "杠铃深蹲", "31", 3, null, null, null, null,
                                MetricsFixtures.confirmed(2))));

        MetricsVos.PlanVersionComparison group = group(service.day(USER, WEDNESDAY), "11");
        // 分母是"这个条目计划 3 组"，不是"两场各 3 组"：条目只出现一次
        assertThat(metric(group.metrics(), MetricsRules.PLANNED_TARGET_SETS).value()).isEqualByComparingTo("3");
        assertThat(metric(group.metrics(), MetricsRules.CONFIRMED_SETS_CAPPED).value()).isEqualByComparingTo("3");
        assertThat(metric(group.metrics(), MetricsRules.EXTRA_SETS).value()).isEqualByComparingTo("1");
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isEqualByComparingTo("100.00");
        assertThat(item(group, "31").sourceSessionIds()).containsExactly("101", "102");
        assertThat(group.sourceSessionIds()).containsExactly("101", "102");
    }

    // ================================================================== null + 原因码

    @Test
    @DisplayName("当天没有任何已确认记录：完成度 null + NO_CONFIRMED_SESSION，计划侧仍完整给出")
    void noConfirmedSessionYieldsNullWithReasonAndKeepsThePlannedSide() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3), bench("32", 3)));
        when(workoutService.completedOn(USER, WEDNESDAY)).thenReturn(List.of());

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        MetricsVos.PlanVersionComparison group = group(result, "11");

        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isNull();
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).unavailableReason())
                .isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
        assertThat(metric(group.metrics(), MetricsRules.CONFIRMED_SETS_CAPPED).unavailableReason())
                .isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
        assertThat(metric(group.metrics(), MetricsRules.EXTRA_SETS).unavailableReason())
                .isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
        assertThat(metric(group.metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS).unavailableReason())
                .isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
        // "安排了 6 组"是事实，与"有没有记录"无关：两组字段各自成立，互不回填
        assertThat(metric(group.metrics(), MetricsRules.PLANNED_TARGET_SETS).value()).isEqualByComparingTo("6");
        assertThat(group.items()).hasSize(2);
        assertThat(item(group, "31").planned().targetSets()).isEqualTo(3);
        assertThat(item(group, "31").actual().confirmedSets()).isZero();
        assertThat(result.recordSummary().completedSessionCount()).isZero();
        assertThat(result.recordSummary().confirmedSetCount()).isZero();
    }

    @Test
    @DisplayName("开了场次但一组都没确认：百分比指标一律 null，不是看起来像事实的 0%")
    void unconfirmedOnlySessionNeverReportsZeroPercent() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 900,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, null, null,
                        MetricsFixtures.notConfirmed(3))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        MetricsVos.PlanVersionComparison group = group(result, "11");
        assertThat(group.metrics()).filteredOn(m -> MetricsRules.UNIT_PERCENT.equals(m.unit()))
                .isNotEmpty()
                .allSatisfy(m -> {
                    assertThat(m.value()).isNull();
                    assertThat(m.unavailableReason()).isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
                });
        assertThat(result.recordSummary().completedSessionCount()).isEqualTo(1);
        assertThat(result.recordSummary().confirmedSetCount()).isZero();
    }

    @Test
    @DisplayName("全是无组数目标的有氧条目：组数完成度 null + NO_TARGET，时长照常给出")
    void cardioOnlyDayHasNoSetsTargetSoCompletionIsNullWithNoTarget() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE",
                MetricsFixtures.planItem("33", "43", "开合跳", 1, null, null, null, 600)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 400,
                MetricsFixtures.entry("63", 1, "43", "开合跳", "33", null, null, null, 600, 300,
                        MetricsFixtures.confirmed(1))));

        MetricsVos.PlanVersionComparison group = group(service.day(USER, WEDNESDAY), "11");
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isNull();
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).unavailableReason())
                .isEqualTo(MetricsRules.NO_TARGET);
        assertThat(metric(group.metrics(), MetricsRules.PLANNED_TARGET_SETS).unavailableReason())
                .isEqualTo(MetricsRules.NO_TARGET);
        assertThat(metric(group.metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS).value()).isEqualByComparingTo("300");
        assertThat(metric(group.metrics(), MetricsRules.CAPPED_MOVEMENT_SECONDS).value()).isEqualByComparingTo("300");
        assertThat(item(group, "33").actual().cappedDurationSeconds()).isEqualTo(300);
    }

    @Test
    @DisplayName("参考的计划版本读不到：不崩、不凭空造分母，缺失项如实标注")
    void unreadablePlanVersionIsReportedInsteadOfFabricatingADenominator() {
        when(planService.findActive(USER)).thenReturn(null);
        when(planService.findById(eq(USER), anyLong()))
                .thenThrow(new BizException(ErrorCode.RESOURCE_NOT_FOUND));
        givenSessions(session("101", WEDNESDAY, "99", "98", 7, 300,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "91", null, null, null, null, null,
                        MetricsFixtures.confirmed(2))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        MetricsVos.PlanVersionComparison group = group(result, "99");
        assertThat(group.missingFields()).anyMatch(m -> m.startsWith(MetricsRules.PLAN_VERSION_UNAVAILABLE));
        assertThat(result.missingFields()).anyMatch(m -> m.startsWith(MetricsRules.PLAN_VERSION_UNAVAILABLE));
        assertThat(metric(group.metrics(), MetricsRules.PLANNED_TARGET_SETS).value()).isNull();
        assertThat(metric(group.metrics(), MetricsRules.PLANNED_TARGET_SETS).unavailableReason())
                .isEqualTo(MetricsRules.PLAN_VERSION_UNAVAILABLE);
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).unavailableReason())
                .isEqualTo(MetricsRules.NO_TARGET);
        assertThat(item(group, "91").planned().unavailableReason())
                .isEqualTo(MetricsRules.PLAN_VERSION_UNAVAILABLE);
    }

    // ================================================================== 时长

    @Test
    @DisplayName("时长未知的条目进 missingFields、不进封顶合计；组数与时长分开返回")
    void unknownDurationIsAMissingFieldNotAZero() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squatWithDuration("31", 3, 600),
                benchWithDuration("32", 3, 600)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 1800,
                // A：确认了 2 组，但一处时长都没记录
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, 600, null,
                        MetricsFixtures.confirmed(2)),
                // B：实际 900 秒，计划目标 600 秒 → 封顶 600
                MetricsFixtures.entry("62", 2, "42", "卧推", "32", 3, null, null, 600, 900,
                        MetricsFixtures.confirmed(2))));

        MetricsVos.PlanVersionComparison group = group(service.day(USER, WEDNESDAY), "11");
        assertThat(metric(group.metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS).value()).isEqualByComparingTo("900");
        assertThat(metric(group.metrics(), MetricsRules.CAPPED_MOVEMENT_SECONDS).value()).isEqualByComparingTo("600");
        assertThat(metric(group.metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS).missingFields())
                .anyMatch(m -> m.startsWith(MetricsRules.DURATION_NOT_RECORDED) && m.contains("31"));
        assertThat(group.missingFields()).anyMatch(m -> m.startsWith(MetricsRules.DURATION_NOT_RECORDED));

        MetricsVos.ItemComparison unknown = item(group, "31");
        assertThat(unknown.actual().recordedDurationSeconds()).isNull();
        assertThat(unknown.actual().cappedDurationSeconds()).isNull();
        assertThat(unknown.actual().durationUnavailableReason()).isEqualTo(MetricsRules.DURATION_NOT_RECORDED);
        assertThat(metric(unknown.metrics(), MetricsRules.ITEM_CAPPED_MOVEMENT_SECONDS).value()).isNull();
        assertThat(metric(unknown.metrics(), MetricsRules.ITEM_RECORDED_MOVEMENT_SECONDS).unavailableReason())
                .isEqualTo(MetricsRules.DURATION_NOT_RECORDED);
        // 组数完成 4/6 与时长是两个独立的量，时长不是组数的换算结果
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isEqualByComparingTo("66.67");
    }

    @Test
    @DisplayName("场次历时与动作实际时长分列，永不相加成\"总活动时长\"")
    void sessionElapsedAndMovementSecondsAreNeverAddedTogether() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squatWithDuration("31", 3, 600)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 3600,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, 600, 900,
                        MetricsFixtures.confirmed(3))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.recordSummary().sessionElapsedSecondsTotal()).isEqualTo(3600);
        assertThat(result.recordSummary().recordedMovementSecondsTotal()).isEqualTo(900);
        assertThat(metric(result.recordSummary().metrics(), MetricsRules.SESSION_ELAPSED_SECONDS).value())
                .isEqualByComparingTo("3600");
        assertThat(metric(result.recordSummary().metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS).value())
                .isEqualByComparingTo("900");
        // 任何一处出现 4500 都说明有人把两者加成了一个谁都没记录过的"总活动时长"
        assertThat(allMetrics(result)).extracting(MetricsVos.Metric::value).doesNotContain(java.math.BigDecimal.valueOf(4500));
        assertThat(allMetrics(result)).extracting(MetricsVos.Metric::code)
                .doesNotContain("TOTAL_ACTIVITY_SECONDS", "ACTIVITY_SECONDS");
    }

    // ================================================================== 次数区间

    @Test
    @DisplayName("次数是否在目标范围单独成列，不影响\"组是否被确认\"")
    void repsInRangeIsReportedSeparatelyFromSetConfirmation() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 600,
                // 10 次在区间内、5 次在区间外、还有一组没记次数
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, 8, 12, null, null,
                        MetricsFixtures.confirmedWithReps(10, 5, null))));

        MetricsVos.PlanVersionComparison group = group(service.day(USER, WEDNESDAY), "11");
        MetricsVos.RepsCheck reps = item(group, "31").reps();
        assertThat(reps.confirmedSets()).isEqualTo(3);
        assertThat(reps.setsWithReps()).isEqualTo(2);
        assertThat(reps.setsInRepsRange()).isEqualTo(1);
        assertThat(reps.setsOutOfRepsRange()).isEqualTo(1);
        assertThat(reps.setsWithoutReps()).isEqualTo(1);
        assertThat(reps.inRangePercent()).isEqualByComparingTo("50.00");
        assertThat(metric(group.metrics(), MetricsRules.REPS_IN_TARGET_RANGE_PCT).value()).isEqualByComparingTo("50.00");
        // 三组都是已确认的：次数不在区间不改变组数完成度
        assertThat(metric(group.metrics(), MetricsRules.CONFIRMED_SETS_CAPPED).value()).isEqualByComparingTo("3");
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("计划没给次数区间：REPS_IN_TARGET_RANGE_PCT 是 null + NO_REPS_TARGET")
    void missingRepsRangeYieldsNullInsteadOfGuessing() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squatWithoutRepsRange("31", 3)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 600,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, null, null,
                        MetricsFixtures.confirmedWithReps(10))));

        MetricsVos.PlanVersionComparison group = group(service.day(USER, WEDNESDAY), "11");
        assertThat(metric(group.metrics(), MetricsRules.REPS_IN_TARGET_RANGE_PCT).value()).isNull();
        assertThat(metric(group.metrics(), MetricsRules.REPS_IN_TARGET_RANGE_PCT).unavailableReason())
                .isEqualTo(MetricsRules.NO_REPS_TARGET);
        // 没给区间就不判定"在不在范围内"：不能因为记了 10 次就倒推一个目标
        assertThat(item(group, "31").reps().targetRangeKnown()).isFalse();
        assertThat(item(group, "31").reps().inRangePercent()).isNull();
        assertThat(metric(item(group, "31").metrics(), MetricsRules.ITEM_REPS_IN_TARGET_RANGE_PCT).unavailableReason())
                .isEqualTo(MetricsRules.NO_REPS_TARGET);
        // 组数口径不受影响：这一组确实被确认了
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isEqualByComparingTo("33.33");
    }

    // ================================================================== 分组与状态

    @Test
    @DisplayName("一天引用两个计划版本：分母各算各的，不合并")
    void twoPlanVersionsOnTheSameDayKeepSeparateDenominators() {
        PlanDetailVo active = wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3));
        PlanDetailVo archived = wednesdayPlan("12", "22", 2, "ARCHIVED", bench("41", 5));
        givenActivePlan(active);
        when(planService.findById(USER, 12L)).thenReturn(archived);
        givenSessions(
                session("101", WEDNESDAY, "11", "21", 3, 600,
                        MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, null, null,
                                MetricsFixtures.confirmed(3))),
                session("102", WEDNESDAY, "12", "22", 2, 600,
                        MetricsFixtures.entry("62", 1, "44", "卧推", "41", 5, null, null, null, null,
                                MetricsFixtures.confirmed(1))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.planVersions()).hasSize(2);
        MetricsVos.PlanVersionComparison v3 = group(result, "11");
        MetricsVos.PlanVersionComparison v2 = group(result, "12");
        assertThat(v3.scheduledForThisDate()).isTrue();
        assertThat(v2.scheduledForThisDate()).isFalse();
        assertThat(v3.planRevisionNo()).isEqualTo(3);
        assertThat(v2.planRevisionNo()).isEqualTo(2);
        assertThat(metric(v3.metrics(), MetricsRules.PLANNED_TARGET_SETS).value()).isEqualByComparingTo("3");
        assertThat(metric(v2.metrics(), MetricsRules.PLANNED_TARGET_SETS).value()).isEqualByComparingTo("5");
        assertThat(metric(v2.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isEqualByComparingTo("20.00");
        assertThat(metric(v2.metrics(), MetricsRules.SET_COMPLETION_PCT).referenceRevisionNo()).isEqualTo(2);
        assertThat(metric(v3.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isEqualByComparingTo("100.00");
        assertThat(v2.sourceSessionIds()).containsExactly("102");
    }

    @Test
    @DisplayName("草稿与已丢弃场次永不参与统计：状态不是 COMPLETED 的一律不看")
    void draftAndDiscardedSessionsNeverEnterTheTotals() {
        when(planService.findActive(USER)).thenReturn(null);
        List<WorkoutSessionEntity> rows = new ArrayList<>();
        rows.add(MetricsFixtures.row(101L, WEDNESDAY, WorkoutSessionStatus.COMPLETED));
        rows.add(MetricsFixtures.row(102L, WEDNESDAY, WorkoutSessionStatus.DRAFT));
        rows.add(MetricsFixtures.row(103L, WEDNESDAY, WorkoutSessionStatus.DISCARDED));
        // 104 是"实体说已确认、明细却是草稿"的脏数据：第二道闸也得拦住它
        rows.add(MetricsFixtures.row(104L, WEDNESDAY, WorkoutSessionStatus.COMPLETED));
        when(workoutService.completedOn(USER, WEDNESDAY)).thenReturn(rows);
        when(workoutService.detail(USER, 101L)).thenReturn(session("101", WEDNESDAY, null, null, null, 600,
                MetricsFixtures.entry("61", 1, "41", "自主动作", null, null, null, null, null, 120,
                        MetricsFixtures.confirmed(2))));
        when(workoutService.detail(USER, 104L)).thenReturn(MetricsFixtures.sessionWithStatus("104", WEDNESDAY,
                "DRAFT", null, null, null, 600));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.recordSummary().completedSessionCount()).isEqualTo(1);
        assertThat(result.recordSummary().sessionIds()).containsExactly("101");
        assertThat(result.planVersions()).isEmpty();
        verify(workoutService, never()).detail(USER, 102L);
        verify(workoutService, never()).detail(USER, 103L);
    }

    @Test
    @DisplayName("自主动作单列：既不进分母，也不抵消别的条目的缺口")
    void selfInitiatedSetsAreListedSeparatelyAndNeverCancelAGap() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 900,
                // 参考条目一组都没确认，另外自主加了 4 组
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, null, null, null, null,
                        MetricsFixtures.notConfirmed(3)),
                MetricsFixtures.entry("62", 2, "49", "自主加练", null, null, null, null, null, 240,
                        MetricsFixtures.confirmed(4))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        MetricsVos.PlanVersionComparison group = group(result, "11");
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).value()).isNull();
        assertThat(metric(group.metrics(), MetricsRules.SET_COMPLETION_PCT).unavailableReason())
                .isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
        assertThat(metric(group.metrics(), MetricsRules.EXTRA_SETS).value()).isNull();
        assertThat(metric(group.items().get(0).metrics(), MetricsRules.ITEM_CONFIRMED_SETS).value()).isNull();

        MetricsVos.SelfInitiatedSummary self = result.selfInitiated();
        assertThat(metric(self.metrics(), MetricsRules.SELF_INITIATED_ENTRY_COUNT).value()).isEqualByComparingTo("1");
        assertThat(metric(self.metrics(), MetricsRules.SELF_INITIATED_CONFIRMED_SETS).value())
                .isEqualByComparingTo("4");
        assertThat(metric(self.metrics(), MetricsRules.SELF_INITIATED_MOVEMENT_SECONDS).value())
                .isEqualByComparingTo("240");
        assertThat(self.entries()).extracting(MetricsVos.SelfInitiatedEntry::entryId).containsExactly("62");
        // 自主动作的时长不进"计划版本"那组的分母，但当天记录摘要看得到它
        assertThat(result.recordSummary().recordedMovementSecondsTotal()).isEqualTo(240);
        assertThat(metric(group.metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS).value()).isNull();
    }

    @Test
    @DisplayName("没有参考计划的自主记录日：不出现任何完成度指标，摘要仍成立")
    void selfRecordedDayWithoutPlanProducesNoCompletionMetrics() {
        when(planService.findActive(USER)).thenReturn(null);
        givenSessions(session("101", WEDNESDAY, null, null, null, 1200,
                MetricsFixtures.entry("61", 1, "49", "自主跑步", null, null, null, null, null, 600,
                        MetricsFixtures.confirmed(1))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.planVersions()).isEmpty();
        assertThat(result.missingFields()).contains(MetricsRules.NO_PLAN_SCHEDULED);
        assertThat(allMetrics(result)).extracting(MetricsVos.Metric::code)
                .doesNotContain(MetricsRules.SET_COMPLETION_PCT, MetricsRules.EXTRA_SETS);
        assertThat(result.recordSummary().recordedMovementSecondsTotal()).isEqualTo(600);
        assertThat(result.recordSummary().sessionElapsedSecondsTotal()).isEqualTo(1200);
    }

    @Test
    @DisplayName("生效计划没排这一天：给出 NO_PLAN_SCHEDULED，不拿别的星期凑分母")
    void activePlanWithoutScheduleForThisWeekdayReportsItAndStaysEmpty() {
        PlanDetailVo plan = MetricsFixtures.plan("11", 3, "ACTIVE", MONDAY, SUNDAY,
                MetricsFixtures.planDay("28", "11", 1, squat("31", 3)));
        givenActivePlan(plan);
        when(workoutService.completedOn(USER, WEDNESDAY)).thenReturn(List.of());

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.planVersions()).isEmpty();
        assertThat(result.missingFields())
                .anyMatch(m -> m.startsWith(MetricsRules.NO_PLAN_SCHEDULED) && m.contains("3"));
        assertThat(result.recordSummary().completedSessionCount()).isZero();
    }

    @Test
    @DisplayName("场次没有参考计划却带着 planItemId：按自主动作处理并标注，不给它凭空造分母")
    void planItemWithoutPlanReferenceIsFlagged() {
        when(planService.findActive(USER)).thenReturn(null);
        givenSessions(session("101", WEDNESDAY, null, null, null, 600,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", null, null, null, null, null,
                        MetricsFixtures.confirmed(2))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.missingFields())
                .anyMatch(m -> m.startsWith(MetricsRules.PLAN_ITEM_WITHOUT_PLAN_REFERENCE) && m.contains("61"));
        assertThat(result.planVersions()).isEmpty();
        assertThat(metric(result.selfInitiated().metrics(), MetricsRules.SELF_INITIATED_CONFIRMED_SETS).value())
                .isEqualByComparingTo("2");
    }

    // ================================================================== 出参自述

    @Test
    @DisplayName("每个指标都自带口径版本、data_revision、单位与来源 session/entry")
    void everyMetricCarriesRuleVersionDataRevisionAndSources() {
        givenActivePlan(wednesdayPlan("11", "21", 3, "ACTIVE", squat("31", 3)));
        givenSessions(session("101", WEDNESDAY, "11", "21", 3, 600,
                MetricsFixtures.entry("61", 1, "41", "杠铃深蹲", "31", 3, 8, 12, null, null,
                        MetricsFixtures.confirmedWithReps(10, 10, 10))));

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        List<MetricsVos.Metric> all = allMetrics(result);
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(metric -> {
            assertThat(metric.ruleVersion()).isEqualTo(MetricsRules.RULE_VERSION);
            assertThat(metric.dataRevision()).isEqualTo("42");
            assertThat(metric.unit()).isNotBlank();
        });
        // 计划版本组的指标必须说明它是按哪一版算的
        MetricsVos.Metric completion = metric(result.planVersions().get(0).metrics(),
                MetricsRules.SET_COMPLETION_PCT);
        assertThat(completion.referencePlanId()).isEqualTo("11");
        assertThat(completion.referenceRevisionNo()).isEqualTo(3);
        assertThat(completion.sourceSessionIds()).containsExactly("101");
        assertThat(metric(result.planVersions().get(0).items().get(0).metrics(), MetricsRules.ITEM_SET_COMPLETION_PCT)
                .sourceEntryIds()).containsExactly("61");
        assertThat(result.ruleVersion()).isEqualTo(MetricsRules.RULE_VERSION);
        assertThat(result.dataRevision()).isEqualTo("42");
    }

    @Test
    @DisplayName("data_revision 读不到时是 null，不是假的 0")
    void unreadableDataRevisionStaysNull() {
        when(revisionReader.currentRevisionText(USER)).thenReturn(null);
        when(planService.findActive(USER)).thenReturn(null);
        when(workoutService.completedOn(USER, WEDNESDAY)).thenReturn(List.of());

        MetricsVos.DayComparison result = service.day(USER, WEDNESDAY);
        assertThat(result.dataRevision()).isNull();
        assertThat(result.recordSummary().metrics()).allSatisfy(metric ->
                assertThat(metric.dataRevision()).isNull());
    }

    // ================================================================== 周统计

    @Test
    @DisplayName("周统计只给场次数/天数/时长与缺失说明，不出现任何百分比")
    void weekSummaryCountsSessionsAndDaysWithoutAnyPercentage() {
        when(workoutService.completedBetween(USER, MONDAY, SUNDAY)).thenReturn(List.of(
                MetricsFixtures.completedRow(201L, MONDAY),
                MetricsFixtures.completedRow(202L, MONDAY),
                MetricsFixtures.completedRow(203L, MONDAY.plusDays(1))));
        when(workoutService.detail(USER, 201L)).thenReturn(session("201", MONDAY, null, null, null, 1000,
                MetricsFixtures.entry("71", 1, "49", "自主动作", null, null, null, null, null, 300,
                        MetricsFixtures.confirmed(2))));
        when(workoutService.detail(USER, 202L)).thenReturn(session("202", MONDAY, null, null, null, 800,
                MetricsFixtures.entry("72", 1, "49", "没记时长的动作", null, null, null, null, null, null,
                        MetricsFixtures.confirmed(1))));
        when(workoutService.detail(USER, 203L)).thenReturn(session("203", MONDAY.plusDays(1), null, null, null, 600,
                MetricsFixtures.entry("73", 1, "49", "自主动作", null, null, null, null, null, 200,
                        MetricsFixtures.confirmed(3))));

        MetricsVos.WeekSummary result = service.week(USER, MONDAY);
        assertThat(result.weekStartDate()).isEqualTo(MONDAY);
        assertThat(result.weekEndDate()).isEqualTo(SUNDAY);
        assertThat(metric(result.metrics(), MetricsRules.COMPLETED_SESSION_COUNT).value()).isEqualByComparingTo("3");
        assertThat(metric(result.metrics(), MetricsRules.DISTINCT_TRAINING_DAY_COUNT).value())
                .isEqualByComparingTo("2");
        assertThat(metric(result.metrics(), MetricsRules.CONFIRMED_SETS_TOTAL).value()).isEqualByComparingTo("6");
        assertThat(metric(result.metrics(), MetricsRules.SESSION_ELAPSED_SECONDS).value()).isEqualByComparingTo("2400");
        // 有依据的动作时长：只累加记录到的 300+200；没记的那条进缺失说明，而不是当 0 秒
        assertThat(metric(result.metrics(), MetricsRules.EVIDENCED_MOVEMENT_SECONDS).value())
                .isEqualByComparingTo("500");
        assertThat(result.missingFields())
                .anyMatch(m -> m.startsWith(MetricsRules.DURATION_NOT_RECORDED) && m.contains("72"));
        assertThat(result.metrics()).extracting(MetricsVos.Metric::code).noneMatch(code -> code.endsWith("_PCT"));
        assertThat(result.days()).hasSize(2);
        assertThat(result.days().get(0).businessDate()).isEqualTo(MONDAY);
        assertThat(result.days().get(0).sessionCount()).isEqualTo(2);
        assertThat(result.days().get(0).recordedMovementSeconds()).isEqualTo(300);
        assertThat(result.days().get(0).confirmedSetCount()).isEqualTo(3);
        assertThat(result.days().get(1).businessDate()).isEqualTo(MONDAY.plusDays(1));
        assertThat(result.days().get(1).sessionCount()).isEqualTo(1);
        assertThat(result.ruleVersion()).isEqualTo(MetricsRules.RULE_VERSION);
        assertThat(result.dataRevision()).isEqualTo("42");
    }

    @Test
    @DisplayName("整周没有任何已确认记录：场次/天数是真实的 0，动作时长是 null + 原因码")
    void emptyWeekReportsRealZeroCountsAndNullDuration() {
        when(workoutService.completedBetween(USER, MONDAY, SUNDAY)).thenReturn(List.of());

        MetricsVos.WeekSummary result = service.week(USER, MONDAY);
        assertThat(metric(result.metrics(), MetricsRules.COMPLETED_SESSION_COUNT).value()).isEqualByComparingTo("0");
        assertThat(metric(result.metrics(), MetricsRules.DISTINCT_TRAINING_DAY_COUNT).value())
                .isEqualByComparingTo("0");
        assertThat(metric(result.metrics(), MetricsRules.EVIDENCED_MOVEMENT_SECONDS).value()).isNull();
        assertThat(metric(result.metrics(), MetricsRules.EVIDENCED_MOVEMENT_SECONDS).unavailableReason())
                .isEqualTo(MetricsRules.NO_CONFIRMED_SESSION);
        assertThat(result.days()).isEmpty();
    }

    @Test
    @DisplayName("周界不是周一：VALIDATION_FAILED + NOT_WEEK_START，不静默挪动区间")
    void weekRejectsStartDateThatIsNotMonday() {
        assertThatThrownBy(() -> service.week(USER, MONDAY.plusDays(1)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException biz = (BizException) e;
                    assertThat(biz.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    List<?> fields = (List<?>) biz.payload();
                    assertThat(fields).hasSize(1);
                    FieldError error = (FieldError) fields.get(0);
                    assertThat(error.field()).isEqualTo("weekStartDate");
                    assertThat(error.code()).isEqualTo("NOT_WEEK_START");
                    assertThat(error.rejected()).isEqualTo(MONDAY.plusDays(1).toString());
                });
    }

    // ================================================================== 夹具与断言小件

    private static PlanItemVo squat(String id, Integer targetSets) {
        return MetricsFixtures.planItem(id, "41", "杠铃深蹲", 1, targetSets, 8, 12, null);
    }

    /** 只给组数目标、不给次数区间：用来验"没目标就不判定"。 */
    private static PlanItemVo squatWithoutRepsRange(String id, Integer targetSets) {
        return MetricsFixtures.planItem(id, "41", "杠铃深蹲", 1, targetSets, null, null, null);
    }

    private static PlanItemVo bench(String id, Integer targetSets) {
        return MetricsFixtures.planItem(id, "44", "卧推", 2, targetSets, 8, 12, null);
    }

    private static PlanItemVo squatWithDuration(String id, Integer targetSets, Integer targetDurationSeconds) {
        return MetricsFixtures.planItem(id, "41", "杠铃深蹲", 1, targetSets, null, null, targetDurationSeconds);
    }

    private static PlanItemVo benchWithDuration(String id, Integer targetSets, Integer targetDurationSeconds) {
        return MetricsFixtures.planItem(id, "44", "卧推", 2, targetSets, null, null, targetDurationSeconds);
    }

    private static WorkoutVos.SessionDetailVo session(String id, LocalDate businessDate, String planId,
                                                      String planDayId, Integer planRevisionNo,
                                                      Integer elapsedSeconds, WorkoutVos.EntryVo... entries) {
        return MetricsFixtures.session(id, businessDate, planId, planDayId, planRevisionNo, elapsedSeconds, entries);
    }

    private static PlanDetailVo wednesdayPlan(String planId, String dayId, Integer revisionNo, String status,
                                              PlanItemVo... items) {
        return MetricsFixtures.plan(planId, revisionNo, status, MONDAY, SUNDAY,
                MetricsFixtures.planDay(dayId, planId, 3, items));
    }

    private void givenActivePlan(PlanDetailVo plan) {
        when(planService.findActive(USER)).thenReturn(plan);
        when(planService.findById(USER, Long.parseLong(plan.id()))).thenReturn(plan);
    }

    private void givenSessions(WorkoutVos.SessionDetailVo... sessions) {
        List<WorkoutSessionEntity> rows = new ArrayList<>();
        for (WorkoutVos.SessionDetailVo session : sessions) {
            rows.add(MetricsFixtures.completedRow(Long.parseLong(session.id()), session.businessDate()));
            when(workoutService.detail(USER, Long.parseLong(session.id()))).thenReturn(session);
        }
        when(workoutService.completedOn(eq(USER), any())).thenReturn(rows);
    }

    private static MetricsVos.PlanVersionComparison group(MetricsVos.DayComparison result, String planId) {
        return result.planVersions().stream().filter(g -> planId.equals(g.planId())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少计划版本分组：" + planId
                        + "，实际 " + result.planVersions().size() + " 组"));
    }

    private static MetricsVos.ItemComparison item(MetricsVos.PlanVersionComparison group, String planItemId) {
        return group.items().stream().filter(i -> planItemId.equals(i.planItemId())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少条目对比行：" + planItemId));
    }

    private static MetricsVos.Metric metric(List<MetricsVos.Metric> metrics, String code) {
        return metrics.stream().filter(m -> code.equals(m.code())).findFirst()
                .orElseThrow(() -> new AssertionError("缺少指标：" + code));
    }

    /** 把一次响应里所有层级的指标摊平，用于"每个指标都必须自带来源"这类全局断言。 */
    private static List<MetricsVos.Metric> allMetrics(MetricsVos.DayComparison result) {
        List<MetricsVos.Metric> all = new ArrayList<>(result.recordSummary().metrics());
        all.addAll(result.selfInitiated().metrics());
        result.planVersions().forEach(group -> {
            all.addAll(group.metrics());
            group.items().forEach(item -> all.addAll(item.metrics()));
        });
        return all;
    }
}
