package com.hfj.fitagent.modules.workout;

import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.modules.training.ExerciseService;
import com.hfj.fitagent.modules.training.TrainingPlanService;
import com.hfj.fitagent.modules.training.vo.ExerciseVo;
import com.hfj.fitagent.modules.training.vo.PlanDayVo;
import com.hfj.fitagent.modules.training.vo.PlanDetailVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.workout.dto.WorkoutRequests;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntity;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionEntryEntity;
import com.hfj.fitagent.modules.workout.entity.WorkoutSessionSetEntity;
import com.hfj.fitagent.modules.workout.mapper.WorkoutSessionEntryMapper;
import com.hfj.fitagent.modules.workout.mapper.WorkoutSessionMapper;
import com.hfj.fitagent.modules.workout.mapper.WorkoutSessionSetMapper;
import com.hfj.fitagent.modules.workout.vo.WorkoutVos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 训练执行的服务语义（阶段 2 验收项 2/3/4/5/8 的单元层证据）。
 *
 * <p>这里证明的是"服务在给定协作方行为下的决定对不对"：幂等、单草稿闸门、参考版本约束、
 * 不替用户确认、删除顺序、乐观锁、归属同形。数据库层的 CHECK / 生成列 / 外键 RESTRICT / 真并发
 * 不在本文件证明范围内（需要真 MySQL 8，见 docs/stage2-acceptance.md）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkoutServiceTest {

    private static final long USER = 7L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-16T02:00:00Z");
    /** 2026-10-16 是周五，所以夹具里的计划日默认 isoWeekday=5 */
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private static final String UUID_A = "2f1c9a88-6a3b-4f2e-9d11-5c7e8a4b0f01";

    @Mock private WorkoutSessionMapper sessionMapper;
    @Mock private WorkoutSessionEntryMapper entryMapper;
    @Mock private WorkoutSessionSetMapper setMapper;
    @Mock private TrainingPlanService planService;
    @Mock private ExerciseService exerciseService;
    @Mock private WorkoutRevisionTicker ticker;

    private WorkoutService service;
    private final AtomicLong ids = new AtomicLong(1000L);
    /** selectOne 与 insert 共用同一个"当前行"，模拟真实库里回读得到同一条记录 */
    private final AtomicReference<WorkoutSessionEntity> current = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new WorkoutService(sessionMapper, entryMapper, setMapper, planService,
                exerciseService, ticker, new AppClock(Clock.fixed(NOW, ZoneOffset.UTC)));

        doAnswer(inv -> {
            WorkoutSessionEntity row = inv.getArgument(0);
            row.setId(ids.incrementAndGet());
            current.set(row);
            return 1;
        }).when(sessionMapper).insert(any(WorkoutSessionEntity.class));
        doAnswer(inv -> {
            ((WorkoutSessionEntryEntity) inv.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(entryMapper).insert(any(WorkoutSessionEntryEntity.class));
        doAnswer(inv -> {
            ((WorkoutSessionSetEntity) inv.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(setMapper).insert(any(WorkoutSessionSetEntity.class));

        when(sessionMapper.selectOne(any())).thenAnswer(inv -> current.get());
        // Mockito 对返回包装类型 Long 的方法默认给 0L 而不是 null，
        // 不显式打桩的话"没有进行中草稿"会被读成"有一个 id=0 的草稿"
        when(sessionMapper.findDraftId(anyLong())).thenReturn(null);
        // BaseMapper 的 updateById/deleteById 都有"按实体"和"按主键"两个重载，matcher 必须带类型
        when(sessionMapper.updateById(any(WorkoutSessionEntity.class))).thenReturn(1);
        when(sessionMapper.deleteById(any(Long.class))).thenReturn(1);
        // 这几个 Mapper 方法收的是原始类型，matcher 必须用 anyLong/anyInt，any() 会因拆箱 NPE
        when(sessionMapper.completeDraft(anyLong(), anyLong(), anyInt(), any(Instant.class))).thenReturn(1);
        when(sessionMapper.discardDraft(anyLong(), anyLong(), anyInt())).thenReturn(1);
        when(sessionMapper.updateCompletedNotes(anyLong(), anyLong(), anyInt(), any())).thenReturn(1);
        when(entryMapper.listBySession(anyLong(), anyLong())).thenReturn(List.of());
        when(setMapper.listBySessions(anyList(), anyLong())).thenReturn(List.of());
        when(exerciseService.mapByIds(any())).thenReturn(Map.of(9L, exercise(9L)));
    }

    // ---------------------------------------------------------------- 开始 / 恢复

    @Test
    @DisplayName("首次开始：新建草稿并快照时区与业务日期，同时递增数据修订号")
    void startCreatesDraft() {
        current.set(null);

        WorkoutVos.StartResultVo result = service.start(USER, ZONE,
                new WorkoutRequests.Start(UUID_A, null, null, null));

        assertThat(result.resumedExisting()).isFalse();
        assertThat(result.session().status()).isEqualTo("DRAFT");
        assertThat(result.session().timezone()).isEqualTo("Asia/Shanghai");
        assertThat(result.session().businessDate()).isEqualTo(TODAY);
        verify(ticker).bump(USER);
    }

    @Test
    @DisplayName("同一幂等键重复开始：返回既有场次，绝不出现第二条")
    void startIsIdempotentByUuid() {
        current.set(draft(500L));

        WorkoutVos.StartResultVo result = service.start(USER, ZONE,
                new WorkoutRequests.Start(UUID_A, null, null, null));

        assertThat(result.resumedExisting()).isTrue();
        assertThat(result.session().id()).isEqualTo("500");
        verify(sessionMapper, never()).insert(any(WorkoutSessionEntity.class));
        verify(ticker, never()).bump(USER);
    }

    @Test
    @DisplayName("已有进行中草稿时再点开始：恢复它，不再新建第二条草稿")
    void startResumesSingleActiveDraft() {
        current.set(draft(501L));
        when(sessionMapper.findDraftId(USER)).thenReturn(501L);

        WorkoutVos.StartResultVo result = service.start(USER, ZONE,
                new WorkoutRequests.Start(UUID_A, null, null, null));

        assertThat(result.resumedExisting()).isTrue();
        assertThat(result.session().id()).isEqualTo("501");
        verify(sessionMapper, never()).insert(any(WorkoutSessionEntity.class));
    }

    @Test
    @DisplayName("并发双开始撞唯一闸门：输家回读赢家那一条，不抛 500 也不留两条草稿")
    void startSurvivesUniqueGateRace() {
        current.set(null);
        doAnswer(inv -> {
            throw new DuplicateKeyException("uq_workout_session_4");
        }).when(sessionMapper).insert(any(WorkoutSessionEntity.class));
        // 撞键后服务会去回读赢家：闸门查询给一个 id，selectOne 就能拿到它
        when(sessionMapper.findDraftId(USER)).thenAnswer(inv -> {
            WorkoutSessionEntity winner = draft(777L);
            current.set(winner);
            return 777L;
        });

        WorkoutVos.StartResultVo result = service.start(USER, ZONE,
                new WorkoutRequests.Start(UUID_A, null, null, null));

        assertThat(result.resumedExisting()).isTrue();
        assertThat(result.session().id()).isEqualTo("777");
    }

    @Test
    @DisplayName("同键但改了业务日期：按幂等冲突拒绝，不静默改写已有草稿")
    void startRejectsSameUuidDifferentIntent() {
        WorkoutSessionEntity existing = draft(500L);
        existing.setBusinessDate(LocalDate.of(2026, 10, 10));
        current.set(existing);

        assertThatThrownBy(() -> service.start(USER, ZONE, new WorkoutRequests.Start(
                UUID_A, null, null, TODAY)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    // ---------------------------------------------------------------- 参考版本约束

    @Test
    @DisplayName("参考草稿版本被拒：计划子项只有草稿态会被删重建，引用它等于引用一个会被抽走的东西")
    void startRejectsDraftPlanReference() {
        current.set(null);
        when(planService.findById(USER, 30L)).thenReturn(plan(30L, "DRAFT", 1, List.of()));

        assertThatThrownBy(() -> service.start(USER, ZONE, new WorkoutRequests.Start(
                UUID_A, "30", "40", TODAY)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("业务日期的星期与所参考计划日不一致：拒绝，避免把周三的动作挂到周五")
    void startRejectsWeekdayMismatch() {
        current.set(null);
        when(planService.findById(USER, 30L)).thenReturn(plan(30L, "ACTIVE", 2,
                List.of(day(40L, 3, List.of()))));

        assertThatThrownBy(() -> service.start(USER, ZONE, new WorkoutRequests.Start(
                UUID_A, "30", "40", TODAY)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("PLAN_DAY_DATE_MISMATCH"));
    }

    @Test
    @DisplayName("补录日期落在所参考计划的起止区间之外：拒绝")
    void startRejectsOutOfRangeDate() {
        current.set(null);
        PlanDetailVo ended = plan(30L, "ARCHIVED", 3, List.of(day(40L, 5, List.of())));
        when(planService.findById(USER, 30L)).thenReturn(new PlanDetailVo(ended.id(), ended.userId(),
                ended.planGroupUuid(), ended.revisionNo(), ended.basePlanId(), ended.title(),
                ended.source(), ended.status(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 12),
                ended.confirmedAt(), ended.lockVersion(), ended.createdAt(), ended.updatedAt(),
                ended.goalSnapshot(), ended.days()));

        assertThatThrownBy(() -> service.start(USER, ZONE, new WorkoutRequests.Start(
                UUID_A, "30", "40", TODAY)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("OUT_OF_PLAN_RANGE"));
    }

    @Test
    @DisplayName("参考已归档版本是允许的：历史执行必须能按当时那个版本回查")
    void startAcceptsArchivedReference() {
        current.set(null);
        when(planService.findById(USER, 30L)).thenReturn(plan(30L, "ARCHIVED", 4,
                List.of(day(40L, 5, List.of(planItem(88L, 3))))));

        WorkoutVos.StartResultVo result = service.start(USER, ZONE,
                new WorkoutRequests.Start(UUID_A, "30", "40", TODAY));

        assertThat(result.session().planRevisionNo()).isEqualTo(4);
        assertThat(result.session().planDayId()).isEqualTo("40");
    }

    // ---------------------------------------------------------------- 保存

    @Test
    @DisplayName("保存必须携带匹配的版本号，否则 409，不做读最新再覆盖")
    void saveRequiresMatchingLockVersion() {
        WorkoutSessionEntity row = draft(600L);
        row.setLockVersion(3);
        current.set(row);

        assertThatThrownBy(() -> service.save(USER, 600L, new WorkoutRequests.Save(2, null, List.of())))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.VERSION_CONFLICT);
        verify(entryMapper, never()).deleteBySession(anyLong(), anyLong());
    }

    @Test
    @DisplayName("聚合重建顺序固定：先删组、再删条目；is_completed 只按入参写，缺失次数保持 null")
    void saveRebuildsSubtreeInForeignKeyOrder() {
        current.set(draft(601L));

        service.save(USER, 601L, new WorkoutRequests.Save(0, null, List.of(
                new WorkoutRequests.EntryInput(1, "9", null, null, null, null, List.of(
                        new WorkoutRequests.SetInput(1, 8, null, null, Boolean.TRUE, null),
                        new WorkoutRequests.SetInput(2, null, null, null, Boolean.FALSE, null))))));

        InOrder order = inOrder(setMapper, entryMapper);
        order.verify(setMapper).deleteBySession(601L, USER);
        order.verify(entryMapper).deleteBySession(601L, USER);

        ArgumentCaptor<WorkoutSessionSetEntity> captor = ArgumentCaptor.forClass(WorkoutSessionSetEntity.class);
        verify(setMapper, atLeastOnce()).insert(captor.capture());
        List<WorkoutSessionSetEntity> sets = captor.getAllValues();
        assertThat(sets).hasSize(2);
        assertThat(sets.get(0).getIsCompleted()).isTrue();
        assertThat(sets.get(1).getIsCompleted()).isFalse();
        assertThat(sets.get(1).getReps()).isNull();
        verify(ticker).bump(USER);
    }

    @Test
    @DisplayName("自主记录不接受任何 planItemId：否则等于凭空造出一个参考分母")
    void saveRejectsPlanItemWithoutReference() {
        current.set(draft(602L));

        assertThatThrownBy(() -> service.save(USER, 602L, new WorkoutRequests.Save(0, null, List.of(
                new WorkoutRequests.EntryInput(1, "9", "777", null, null, null, List.of())))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("PLAN_ITEM_MISMATCH"));
    }

    @Test
    @DisplayName("参考计划日下的条目可以引用；同一条目被拆两条则拒绝")
    void saveAcceptsOwnedPlanItemAndRejectsDuplicate() {
        WorkoutSessionEntity row = draft(604L);
        row.setPlanId(30L);
        row.setPlanDayId(40L);
        current.set(row);
        when(planService.findById(USER, 30L)).thenReturn(plan(30L, "ACTIVE", 1,
                List.of(day(40L, 5, List.of(planItem(88L, 3))))));

        service.save(USER, 604L, new WorkoutRequests.Save(0, null, List.of(
                new WorkoutRequests.EntryInput(1, "9", "88", null, null, null, List.of()))));

        assertThatThrownBy(() -> service.save(USER, 604L, new WorkoutRequests.Save(0, null, List.of(
                new WorkoutRequests.EntryInput(1, "9", "88", null, null, null, List.of()),
                new WorkoutRequests.EntryInput(2, "9", "88", null, null, null, List.of())))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("DUPLICATE_PLAN_ITEM"));
    }

    @Test
    @DisplayName("组序号重复直接拒绝")
    void saveRejectsDuplicateSetNo() {
        current.set(draft(603L));

        assertThatThrownBy(() -> service.save(USER, 603L, new WorkoutRequests.Save(0, null, List.of(
                new WorkoutRequests.EntryInput(1, "9", null, null, null, null, List.of(
                        new WorkoutRequests.SetInput(1, 5, null, null, Boolean.TRUE, null),
                        new WorkoutRequests.SetInput(1, 5, null, null, Boolean.TRUE, null)))))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("INVALID_SET_NO"));
    }

    @Test
    @DisplayName("动作不在字典里就拒绝，不允许留一条没有名字的实际记录")
    void saveRejectsUnknownExercise() {
        current.set(draft(605L));
        when(exerciseService.mapByIds(any())).thenReturn(Map.of());

        assertThatThrownBy(() -> service.save(USER, 605L, new WorkoutRequests.Save(0, null, List.of(
                new WorkoutRequests.EntryInput(1, "404", null, null, null, null, List.of())))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("EXERCISE_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- 结束 / 丢弃 / 删除

    @Test
    @DisplayName("确认结束只迁移一次；重复结束是幂等成功，不再写库也不重复递增修订号")
    void completeIsIdempotent() {
        WorkoutSessionEntity done = draft(700L);
        done.setStatus(WorkoutSessionStatus.COMPLETED);
        done.setEndedAt(NOW);
        current.set(done);

        service.complete(USER, 700L, new WorkoutRequests.Complete(0, null));
        service.complete(USER, 700L, new WorkoutRequests.Complete(0, null));

        verify(sessionMapper, never()).completeDraft(anyLong(), anyLong(), anyInt(), any(Instant.class));
        verify(ticker, never()).bump(USER);
    }

    @Test
    @DisplayName("从草稿结束：写一次 ended_at 并递增修订号")
    void completeFromDraftWritesOnce() {
        current.set(draft(705L));

        service.complete(USER, 705L, new WorkoutRequests.Complete(0, null));

        verify(sessionMapper, times(1)).completeDraft(eq(705L), eq(USER), eq(0), any(Instant.class));
        verify(ticker).bump(USER);
    }

    @Test
    @DisplayName("结束时间早于开始时间：拒绝，不写一个负历时")
    void completeRejectsEndedBeforeStarted() {
        current.set(draft(701L));

        assertThatThrownBy(() -> service.complete(USER, 701L,
                new WorkoutRequests.Complete(0, NOW.minusSeconds(60))))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(fieldCode(e)).isEqualTo("ENDED_BEFORE_STARTED"));
    }

    @Test
    @DisplayName("已确认结束的记录不能走丢弃：必须用删除，避免把正式统计悄悄抹掉")
    void discardRejectsCompleted() {
        WorkoutSessionEntity done = draft(702L);
        done.setStatus(WorkoutSessionStatus.COMPLETED);
        current.set(done);

        assertThatThrownBy(() -> service.discard(USER, 702L, new WorkoutRequests.VersionOnly(1)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
    }

    @Test
    @DisplayName("删除按 set → entry → session 的父子顺序，反了会撞外键")
    void deleteWalksChildrenFirst() {
        current.set(draft(703L));

        service.delete(USER, 703L);

        InOrder order = inOrder(setMapper, entryMapper, sessionMapper);
        order.verify(setMapper).deleteBySession(703L, USER);
        order.verify(entryMapper).deleteBySession(703L, USER);
        order.verify(sessionMapper).deleteById(703L);
        verify(ticker).bump(USER);
    }

    // ---------------------------------------------------------------- 归属

    @Test
    @DisplayName("查别人的场次一律 404，与不存在同形，不泄露资源是否存在")
    void otherUsersSessionLooksMissing() {
        current.set(null);

        assertThatThrownBy(() -> service.detail(USER, 9999L))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---------------------------------------------------------------- 夹具

    private static String fieldCode(Throwable e) {
        Object payload = ((BizException) e).payload();
        if (payload instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof FieldError first) {
            return first.code();
        }
        return null;
    }

    private static WorkoutSessionEntity draft(long id) {
        WorkoutSessionEntity row = new WorkoutSessionEntity();
        row.setId(id);
        row.setUserId(USER);
        row.setClientRecordUuid(UUID_A);
        row.setBusinessDate(TODAY);
        row.setTimezone("Asia/Shanghai");
        row.setStartedAt(NOW);
        row.setStatus(WorkoutSessionStatus.DRAFT);
        row.setLockVersion(0);
        return row;
    }

    private static PlanDetailVo plan(long id, String status, int revisionNo, List<PlanDayVo> days) {
        return new PlanDetailVo(String.valueOf(id), String.valueOf(USER), "grp", revisionNo, null,
                "下肢与上肢拉", "MANUAL", status, LocalDate.of(2026, 10, 1), null,
                NOW, 0, NOW, NOW, null, days);
    }

    private static PlanDayVo day(long id, int isoWeekday, List<PlanItemVo> items) {
        return new PlanDayVo(String.valueOf(id), "30", isoWeekday, "拉", Boolean.FALSE, null, items);
    }

    private static PlanItemVo planItem(long id, int targetSets) {
        return new PlanItemVo(String.valueOf(id), "9", "SQUAT", "深蹲", "STRENGTH", 1,
                targetSets, 8, 10, null, null, 120, null);
    }

    private static ExerciseVo exercise(long id) {
        return new ExerciseVo(String.valueOf(id), "SQUAT", "深蹲", "STRENGTH",
                List.of("QUADRICEPS"), List.of("BARBELL"), null, null);
    }
}
