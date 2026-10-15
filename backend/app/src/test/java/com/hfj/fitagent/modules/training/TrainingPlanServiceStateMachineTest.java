package com.hfj.fitagent.modules.training;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.profile.api.ProfileContextReader;
import com.hfj.fitagent.modules.training.entity.GoalSnapshot;
import com.hfj.fitagent.modules.training.entity.TrainingPlanEntity;
import com.hfj.fitagent.modules.training.mapper.TrainingPlanDayMapper;
import com.hfj.fitagent.modules.training.mapper.TrainingPlanItemMapper;
import com.hfj.fitagent.modules.training.mapper.TrainingPlanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划状态机的单元测试（docs/p1-api.md §7 迁移矩阵、docs/p1-database.md §6 跨表约束）。
 *
 * 这里能证明的是<b>应用侧</b>那半边：加锁与归档的先后顺序、版本不符不写入、
 * 冲突不递增修订号、丢弃不物理删行、另存新草稿不碰来源版本。
 * "并发激活只留一个 ACTIVE"（验收 A12）与 CHECK 真实拦截（A14）依赖数据库，
 * 必须由 MySQL 8.4 上的集成测试补证，本类不冒充那个结论。
 */
@DisplayName("TrainingPlanService 状态机")
class TrainingPlanServiceStateMachineTest {

    private static final long USER = 7L;
    private static final long PLAN = 9L;
    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");

    private TrainingPlanMapper planMapper;
    private TrainingPlanDayMapper dayMapper;
    private TrainingPlanItemMapper itemMapper;
    private PlanRevisionTicker revisionTicker;
    private TrainingPlanService service;

    @BeforeEach
    void setUp() {
        planMapper = mock(TrainingPlanMapper.class);
        dayMapper = mock(TrainingPlanDayMapper.class);
        itemMapper = mock(TrainingPlanItemMapper.class);
        revisionTicker = mock(PlanRevisionTicker.class);

        AppClock clock = new AppClock(Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")));
        ObjectMapper mapper = new JsonMapper();
        service = new TrainingPlanService(planMapper, dayMapper, itemMapper,
                mock(ExerciseDictionary.class), mock(PlanAggregateValidator.class),
                mock(ProfileContextReader.class), revisionTicker, clock, mapper);

        // 读聚合时子行为空即可：本类只断言头部的写入顺序与版本语义
        lenient().when(dayMapper.listByPlan(anyLong(), anyLong())).thenReturn(List.of());
        lenient().when(itemMapper.listByPlan(anyLong(), anyLong())).thenReturn(List.of());
    }

    private TrainingPlanEntity plan(String status, int lockVersion) {
        TrainingPlanEntity entity = new TrainingPlanEntity();
        entity.setId(PLAN);
        entity.setUserId(USER);
        entity.setPlanGroupUuid("3f1a2b4c-0000-4000-8000-000000000001");
        entity.setRevisionNo(2);
        entity.setTitle("示例计划");
        entity.setSource("MANUAL");
        entity.setStatus(status);
        entity.setGoalSnapshot(GoalSnapshot.withoutGoal(NOW, null, null, List.of()));
        entity.setStartsOn(LocalDate.of(2026, 10, 12));
        entity.setEndsOn(null);
        entity.setLockVersion(lockVersion);
        return entity;
    }

    private void stubRead(TrainingPlanEntity entity) {
        when(planMapper.selectOne(any())).thenReturn(entity);
    }

    @Test
    @DisplayName("确认生效：锁账户 → 锁计划 → 先归档旧 ACTIVE → 再激活草稿 → 才递增修订号")
    void activateArchivesBeforeActivating() {
        stubRead(plan(PlanStatus.DRAFT.name(), 3));
        when(planMapper.lockAccount(USER)).thenReturn(USER);
        when(planMapper.lockPlan(PLAN, USER)).thenReturn(PLAN);
        when(planMapper.activateDraft(eq(PLAN), eq(USER), eq(3), any(Instant.class))).thenReturn(1);

        service.activate(USER, PLAN, 3);

        // 顺序颠倒会让 UNIQUE(active_user_id) 在同一事务里就看到两行同 user_id
        InOrder order = inOrder(planMapper);
        order.verify(planMapper).lockAccount(USER);
        order.verify(planMapper).lockPlan(PLAN, USER);
        order.verify(planMapper).archiveActive(USER);
        order.verify(planMapper).activateDraft(PLAN, USER, 3, NOW);
        verify(revisionTicker).bump(USER);
    }

    @Test
    @DisplayName("只有草稿能生效：ACTIVE 直接生效 → INVALID_STATE_TRANSITION，且不归档不激活")
    void activateRejectsNonDraft() {
        stubRead(plan(PlanStatus.ACTIVE.name(), 3));
        when(planMapper.lockAccount(USER)).thenReturn(USER);
        when(planMapper.lockPlan(PLAN, USER)).thenReturn(PLAN);

        assertThatThrownBy(() -> service.activate(USER, PLAN, 3))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));

        verify(planMapper, never()).archiveActive(anyLong());
        verify(planMapper, never()).activateDraft(anyLong(), anyLong(), anyInt(), any());
        verify(revisionTicker, never()).bump(anyLong());
    }

    @Test
    @DisplayName("lockVersion 不符 → VERSION_CONFLICT，归档动作一次都不发生")
    void activateRejectsStaleVersion() {
        stubRead(plan(PlanStatus.DRAFT.name(), 2));
        when(planMapper.lockAccount(USER)).thenReturn(USER);
        when(planMapper.lockPlan(PLAN, USER)).thenReturn(PLAN);

        assertThatThrownBy(() -> service.activate(USER, PLAN, 5))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));

        verify(planMapper, never()).archiveActive(anyLong());
        verify(revisionTicker, never()).bump(anyLong());
    }

    @Test
    @DisplayName("激活语句影响 0 行（被别人抢先）→ VERSION_CONFLICT，不记修订号")
    void activateLosingRaceDoesNotBumpRevision() {
        stubRead(plan(PlanStatus.DRAFT.name(), 3));
        when(planMapper.lockAccount(USER)).thenReturn(USER);
        when(planMapper.lockPlan(PLAN, USER)).thenReturn(PLAN);
        when(planMapper.activateDraft(anyLong(), anyLong(), anyInt(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.activate(USER, PLAN, 3))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(revisionTicker, never()).bump(anyLong());
    }

    @Test
    @DisplayName("UNIQUE(active_user_id) 拦下并发激活 → ACTIVE_PLAN_EXISTS，修订号不加")
    void concurrentActivationSurfacesUniqueIndex() {
        stubRead(plan(PlanStatus.DRAFT.name(), 3));
        when(planMapper.lockAccount(USER)).thenReturn(USER);
        when(planMapper.lockPlan(PLAN, USER)).thenReturn(PLAN);
        when(planMapper.archiveActive(USER)).thenThrow(new DuplicateKeyException("uq_training_plan_4"));

        assertThatThrownBy(() -> service.activate(USER, PLAN, 3))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.ACTIVE_PLAN_EXISTS));
        verify(revisionTicker, never()).bump(anyLong());
    }

    @Test
    @DisplayName("丢弃草稿只改状态：不物理删除计划日与动作条目")
    void discardKeepsHistoryRows() {
        stubRead(plan(PlanStatus.DRAFT.name(), 1));
        when(planMapper.discardDraft(PLAN, USER, 1)).thenReturn(1);

        service.discard(USER, PLAN, 1);

        verify(planMapper).discardDraft(PLAN, USER, 1);
        verify(revisionTicker).bump(USER);
        // 历史一旦能删，"计划不可覆盖"就没法证明：这里锁死没有任何删除动作
        verify(itemMapper, never()).deleteByPlan(anyLong(), anyLong());
        verify(dayMapper, never()).deleteByPlan(anyLong(), anyLong());
    }

    @Test
    @DisplayName("已生效的计划不能丢弃，只能归档")
    void discardRejectsActivePlan() {
        stubRead(plan(PlanStatus.ACTIVE.name(), 1));

        assertThatThrownBy(() -> service.discard(USER, PLAN, 1))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(planMapper, never()).discardDraft(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("归档当前计划：仅 ACTIVE；影响 0 行按版本冲突处理")
    void archiveOnlyAffectsActiveAndChecksVersion() {
        stubRead(plan(PlanStatus.ACTIVE.name(), 4));
        when(planMapper.archiveById(PLAN, USER, 4)).thenReturn(1);

        service.archive(USER, PLAN, 4);

        verify(planMapper).archiveById(PLAN, USER, 4);
        verify(revisionTicker).bump(USER);

        when(planMapper.archiveById(PLAN, USER, 9)).thenReturn(0);
        assertThatThrownBy(() -> service.archive(USER, PLAN, 9))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
    }

    @Test
    @DisplayName("修改生效中的计划：同组新 DRAFT(revisionNo=max+1)，来源版本一行都不动")
    void reviseCreatesNewRevisionWithoutTouchingSource() {
        TrainingPlanEntity source = plan(PlanStatus.ACTIVE.name(), 0);
        stubRead(source);
        when(planMapper.findMaxRevisionNo(USER, source.getPlanGroupUuid())).thenReturn(2);
        // Mockito 不会像 MyBatis 那样回填自增 id，不赋值的话后续 loadAggregate 拿不到主键
        when(planMapper.insert(any(TrainingPlanEntity.class))).thenAnswer(inv -> {
            inv.getArgument(0, TrainingPlanEntity.class).setId(42L);
            return 1;
        });

        service.revise(USER, PLAN);

        ArgumentCaptor<TrainingPlanEntity> created = ArgumentCaptor.forClass(TrainingPlanEntity.class);
        verify(planMapper).insert(created.capture());
        TrainingPlanEntity draft = created.getValue();
        assertThat(draft.getStatus()).isEqualTo(PlanStatus.DRAFT.name());
        assertThat(draft.getRevisionNo()).isEqualTo(3);
        assertThat(draft.getBasePlanId()).isEqualTo(PLAN);
        assertThat(draft.getPlanGroupUuid()).isEqualTo(source.getPlanGroupUuid());

        // 验收 A11：已确认版本不被覆盖
        verify(planMapper, never()).updateById(any(TrainingPlanEntity.class));
        verify(itemMapper, never()).deleteByPlan(eq(PLAN), anyLong());
        verify(dayMapper, never()).deleteByPlan(eq(PLAN), anyLong());
    }

    @Test
    @DisplayName("草稿不另存新版本，而是就地编辑")
    void reviseRejectsDraft() {
        stubRead(plan(PlanStatus.DRAFT.name(), 0));

        assertThatThrownBy(() -> service.revise(USER, PLAN))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(planMapper, never()).insert(any(TrainingPlanEntity.class));
    }

    @Test
    @DisplayName("越权访问他人计划：查询恒带 user_id，取不到即 RESOURCE_NOT_FOUND")
    void readsAreScopedToOwner() {
        when(planMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.findById(USER, PLAN))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
