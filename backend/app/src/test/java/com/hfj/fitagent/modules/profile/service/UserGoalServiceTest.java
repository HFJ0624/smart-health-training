package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.common.web.SortColumns;
import com.hfj.fitagent.modules.auth.mapper.UserAccountMapper;
import com.hfj.fitagent.modules.profile.controller.GoalController;
import com.hfj.fitagent.modules.profile.dto.CreateGoalRequest;
import com.hfj.fitagent.modules.profile.dto.UpdateGoalRequest;
import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;
import com.hfj.fitagent.modules.profile.mapper.UserGoalMapper;
import com.hfj.fitagent.modules.profile.vo.GoalVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 目标状态机与"至多一个 ACTIVE"的事务内顺序。无 Spring 上下文、无数据库：
 * 唯一索引本身只能等真实 MySQL 8.4 验证（验收 A12），这里证明的是应用侧的调用顺序与拒绝路径。
 */
class UserGoalServiceTest {

    private static final long USER = 7L;
    private static final long GOAL_ID = 21L;
    private static final LocalDate STARTS = LocalDate.of(2026, 10, 12);
    private static final LocalDate ENDS = LocalDate.of(2027, 1, 31);

    private UserGoalMapper goalMapper;
    private UserAccountMapper accountMapper;
    private UserGoalService service;

    @BeforeEach
    void setUp() {
        goalMapper = mock(UserGoalMapper.class);
        accountMapper = mock(UserAccountMapper.class);
        service = new UserGoalService(goalMapper, accountMapper);
        when(accountMapper.lockById(USER)).thenReturn(USER);
        when(accountMapper.incrementDataRevision(USER)).thenReturn(1);
    }

    private static UserGoalEntity goal(String status, int lockVersion) {
        UserGoalEntity entity = new UserGoalEntity();
        entity.setId(GOAL_ID);
        entity.setUserId(USER);
        entity.setGoalType("FAT_LOSS");
        entity.setTargetWeightKg(new BigDecimal("70.00"));
        entity.setWeeklySessionsTarget(4);
        entity.setStartsOn(STARTS);
        entity.setEndsOn(ENDS);
        entity.setStatus(status);
        entity.setLockVersion(lockVersion);
        return entity;
    }

    private static CreateGoalRequest createRequest(boolean activate) {
        return new CreateGoalRequest("FAT_LOSS", new BigDecimal("70.00"), 4, STARTS, ENDS, activate);
    }

    private static UpdateGoalRequest updateRequest(Integer lockVersion) {
        return new UpdateGoalRequest("MUSCLE_GAIN", new BigDecimal("75.00"), 5, STARTS, ENDS, lockVersion);
    }

    /** 模拟 MyBatis-Plus 在 INSERT 后回填自增主键与初始版本号。 */
    private void stubInsertAssigningIdentity() {
        when(goalMapper.insert(any(UserGoalEntity.class))).thenAnswer(invocation -> {
            UserGoalEntity entity = invocation.getArgument(0);
            entity.setId(GOAL_ID);
            entity.setLockVersion(0);
            return 1;
        });
    }

    @Test
    @DisplayName("新建即激活：先落 DRAFT，再锁账户行 → 归档旧 ACTIVE → 激活本草稿，顺序不可颠倒")
    void createWithActivateArchivesBeforeActivating() {
        stubInsertAssigningIdentity();
        when(goalMapper.archiveActive(USER)).thenReturn(1);
        when(goalMapper.activateDraft(GOAL_ID, USER, 0)).thenReturn(1);
        when(goalMapper.selectOne(any())).thenReturn(goal("ACTIVE", 1));

        GoalVo vo = service.create(USER, createRequest(true));

        InOrder order = inOrder(goalMapper, accountMapper);
        order.verify(goalMapper).insert(any(UserGoalEntity.class));
        order.verify(accountMapper).lockById(USER);
        order.verify(goalMapper).archiveActive(USER);
        order.verify(goalMapper).activateDraft(GOAL_ID, USER, 0);
        order.verify(accountMapper).incrementDataRevision(USER);
        assertThat(vo.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("新建为草稿时不做任何状态迁移，也不锁账户行")
    void createAsDraftDoesNotTouchActive() {
        stubInsertAssigningIdentity();
        when(goalMapper.selectOne(any())).thenReturn(goal("DRAFT", 0));

        service.create(USER, createRequest(false));

        verify(goalMapper, never()).archiveActive(anyLong());
        verify(goalMapper, never()).activateDraft(anyLong(), anyLong(), anyInt());
        verify(accountMapper, never()).lockById(anyLong());
        verify(accountMapper).incrementDataRevision(USER);
    }

    @Test
    @DisplayName("创建时 INSERT 的 status 恒为 DRAFT：激活只由 activateDraft 完成，全程不写 active_user_id")
    void createdRowStartsAsDraft() {
        stubInsertAssigningIdentity();
        when(goalMapper.activateDraft(GOAL_ID, USER, 0)).thenReturn(1);
        when(goalMapper.selectOne(any())).thenReturn(goal("ACTIVE", 1));

        service.create(USER, createRequest(true));

        ArgumentCaptor<UserGoalEntity> captor = ArgumentCaptor.forClass(UserGoalEntity.class);
        verify(goalMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(captor.getValue().getUserId()).isEqualTo(USER);
        // daily_intake_target_kcal 本轮不写：实体里的值保持未赋值（由 DB 默认 NULL）
        assertThat(captor.getValue().getDailyIntakeTargetKcal()).isNull();
    }

    @Test
    @DisplayName("并发激活撞 UNIQUE(active_user_id) → 409，事务回滚后不加修订号")
    void concurrentActivationBecomesVersionConflict() {
        stubInsertAssigningIdentity();
        when(goalMapper.archiveActive(USER)).thenReturn(1);
        when(goalMapper.activateDraft(GOAL_ID, USER, 0)).thenThrow(
                new org.springframework.dao.DuplicateKeyException("uq_user_goal_3"));

        assertThatThrownBy(() -> service.create(USER, createRequest(true)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(accountMapper, never()).incrementDataRevision(anyLong());
    }

    @Test
    @DisplayName("ARCHIVED 目标不可修改 → INVALID_STATE_TRANSITION，且不写库")
    void updateOnArchivedGoalRejected() {
        when(goalMapper.selectOne(any())).thenReturn(goal("ARCHIVED", 4));

        assertThatThrownBy(() -> service.update(USER, GOAL_ID, updateRequest(4)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);
                    assertThat(e.getMessage()).contains("已归档");
                });
        verify(goalMapper, never()).updateById(any(UserGoalEntity.class));
    }

    @Test
    @DisplayName("DRAFT 与 ACTIVE 都可就地改")
    void updateAllowedForDraftAndActive() {
        when(goalMapper.selectOne(any())).thenReturn(goal("DRAFT", 4));
        when(goalMapper.updateById(any(UserGoalEntity.class))).thenReturn(1);

        GoalVo vo = service.update(USER, GOAL_ID, updateRequest(4));

        ArgumentCaptor<UserGoalEntity> captor = ArgumentCaptor.forClass(UserGoalEntity.class);
        verify(goalMapper).updateById(captor.capture());
        assertThat(captor.getValue().getGoalType()).isEqualTo("MUSCLE_GAIN");
        // 状态不由 PUT 改动
        assertThat(captor.getValue().getStatus()).isEqualTo("DRAFT");
        verify(accountMapper).incrementDataRevision(USER);
        assertThat(vo).isNotNull();
    }

    @Test
    @DisplayName("目标 PUT 的 lockVersion 不符 → 409")
    void updateWithStaleVersionRejected() {
        when(goalMapper.selectOne(any())).thenReturn(goal("DRAFT", 4));

        assertThatThrownBy(() -> service.update(USER, GOAL_ID, updateRequest(3)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.VERSION_CONFLICT));
        verify(goalMapper, never()).updateById(any(UserGoalEntity.class));
    }

    @Test
    @DisplayName("endsOn 早于 startsOn → 定位到 endsOn 的字段错误（对齐 ck_user_goal_2）")
    void endsBeforeStartsRejected() {
        when(goalMapper.selectOne(any())).thenReturn(goal("DRAFT", 4));

        assertThatThrownBy(() -> service.update(USER, GOAL_ID,
                new UpdateGoalRequest("FAT_LOSS", null, null, STARTS, STARTS.minusDays(1), 4)))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.payload()).singleElement().satisfies(f -> {
                        FieldError fieldError = (FieldError) f;
                        assertThat(fieldError.field()).isEqualTo("endsOn");
                        assertThat(fieldError.code()).isEqualTo("ENDS_BEFORE_STARTS");
                    });
                });
    }

    @Test
    @DisplayName("删除只允许 DRAFT：ACTIVE 与 ARCHIVED 都拒绝")
    void deleteOnlyAllowsDraft() {
        when(goalMapper.selectOne(any())).thenReturn(goal("ACTIVE", 2));

        assertThatThrownBy(() -> service.delete(USER, GOAL_ID))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(goalMapper, never()).delete(any());

        when(goalMapper.selectOne(any())).thenReturn(goal("ARCHIVED", 2));
        assertThatThrownBy(() -> service.delete(USER, GOAL_ID))
                .isInstanceOf(BizException.class);
        verify(goalMapper, never()).delete(any());
    }

    @Test
    @DisplayName("删除 DRAFT 成功并递增修订号")
    void deleteDraftSucceeds() {
        when(goalMapper.selectOne(any())).thenReturn(goal("DRAFT", 2));
        when(goalMapper.delete(any())).thenReturn(1);

        service.delete(USER, GOAL_ID);

        ArgumentCaptor<QueryWrapper<UserGoalEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(goalMapper).delete(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("user_id");
        verify(accountMapper).incrementDataRevision(USER);
    }

    @Test
    @DisplayName("activate 只接受 DRAFT；ACTIVE/ARCHIVED 一律拒绝，不做静默幂等")
    void activateRejectsNonDraft() {
        when(goalMapper.selectOne(any())).thenReturn(goal("ACTIVE", 2));

        assertThatThrownBy(() -> service.activate(USER, GOAL_ID, 2))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(goalMapper, never()).archiveActive(anyLong());
    }

    @Test
    @DisplayName("archive 只接受 ACTIVE；DRAFT 归档是伪历史，拒绝")
    void archiveRejectsDraft() {
        when(goalMapper.selectOne(any())).thenReturn(goal("DRAFT", 2));

        assertThatThrownBy(() -> service.archive(USER, GOAL_ID, 2))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verify(goalMapper, never()).updateById(any(UserGoalEntity.class));
    }

    @Test
    @DisplayName("archive 成功后状态为 ARCHIVED，且仍不写 active_user_id（由数据库派生为 NULL）")
    void archiveActiveGoal() {
        when(goalMapper.selectOne(any())).thenReturn(goal("ACTIVE", 2));
        when(goalMapper.updateById(any(UserGoalEntity.class))).thenReturn(1);

        GoalVo vo = service.archive(USER, GOAL_ID, 2);

        ArgumentCaptor<UserGoalEntity> captor = ArgumentCaptor.forClass(UserGoalEntity.class);
        verify(goalMapper).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ARCHIVED");
        assertThat(captor.getValue().getUserId()).isEqualTo(USER);
        assertThat(vo).isNotNull();
    }

    @Test
    @DisplayName("状态迁移必须携带 lockVersion")
    void transitionRequiresLockVersion() {
        assertThatThrownBy(() -> service.activate(USER, GOAL_ID, null))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).code())
                            .containsExactly("REQUIRED");
                });
        assertThatThrownBy(() -> service.archive(USER, GOAL_ID, null))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("越权访问他人目标：(id,user_id) 查不到 → RESOURCE_NOT_FOUND，不泄露是否存在")
    void otherUsersGoalIsNotFound() {
        when(goalMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.update(USER, GOAL_ID, updateRequest(0)))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.activate(USER, GOAL_ID, 0))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        assertThatThrownBy(() -> service.delete(USER, GOAL_ID))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));

        // 每次按 id 取行的查询都带上了调用者的 user_id，别人的行在条件层就被排除
        ArgumentCaptor<QueryWrapper<UserGoalEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(goalMapper, org.mockito.Mockito.atLeastOnce()).selectOne(captor.capture());
        QueryWrapper<UserGoalEntity> last = captor.getValue();
        assertThat(last.getSqlSegment()).contains("id").contains("user_id");
        assertThat(last.getParamNameValuePairs().values()).contains(USER, GOAL_ID);
    }

    @Test
    @DisplayName("当前 ACTIVE：无目标返回 null（空状态，不是 404）")
    void findActiveWhenNone() {
        when(goalMapper.findActiveId(USER)).thenReturn(null);

        assertThat(service.findActive(USER)).isNull();
    }

    @Test
    @DisplayName("当前 ACTIVE 命中后按 (id,user_id) 取完整行")
    void findActiveReturnsTheRow() {
        when(goalMapper.findActiveId(USER)).thenReturn(GOAL_ID);
        when(goalMapper.selectOne(any())).thenReturn(goal("ACTIVE", 1));

        GoalVo vo = service.findActive(USER);

        assertThat(vo).isNotNull();
        assertThat(vo.status()).isEqualTo("ACTIVE");
        // 本轮不暴露饮食目标：响应里恒为 null
        assertThat(vo.dailyIntakeTargetKcal()).isNull();
    }

    @Test
    @DisplayName("列表排序列来自白名单映射，非法字段在进入 SQL 之前就被拒绝")
    void listUsesWhitelistedSortColumn() {
        Page<UserGoalEntity> page = new Page<>(1, 20);
        page.setRecords(List.of(goal("ACTIVE", 1)));
        page.setTotal(1L);
        when(goalMapper.selectPage(any(), any())).thenReturn(page);

        PageQuery query = PageQuery.of(1, 20, "startsOn,asc", GoalController.SORT.allowedFields(),
                GoalController.DEFAULT_SORT_FIELD);
        PageResult<GoalVo> result = service.list(USER, query, GoalController.SORT, null);

        ArgumentCaptor<QueryWrapper<UserGoalEntity>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(goalMapper).selectPage(any(), captor.capture());
        // 排序用的列名只可能是白名单映射出来的那个，属性名本身没有进入 SQL 的入口
        assertThat(query.column(GoalController.SORT)).isEqualTo("starts_on");
        // 白名单是成员关系，不是顺序契约
        assertThat(GoalController.SORT.allowedFields()).containsOnly("createdAt", "startsOn");
        assertThat(captor.getValue().getSqlSegment()).contains("user_id");
        assertThat(result.items()).hasSize(1);
        assertThat(result.total()).isEqualTo(1L);
    }

    @Test
    @DisplayName("非法排序字段与超上限 size 由 PageQuery 统一拒绝")
    void illegalSortFieldRejected() {
        assertThatThrownBy(() -> PageQuery.of(1, 20, "goalType,desc", GoalController.SORT.allowedFields(),
                GoalController.DEFAULT_SORT_FIELD))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).code())
                            .containsExactly("INVALID_SORT_FIELD");
                });

        assertThatThrownBy(() -> PageQuery.of(1, 101, null, GoalController.SORT.allowedFields(),
                GoalController.DEFAULT_SORT_FIELD))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("status 过滤只接受大写枚举；非法值定位到 status 字段")
    void illegalStatusFilterRejected() {
        assertThatThrownBy(() -> service.list(USER,
                PageQuery.of(1, 20, null, GoalController.SORT.allowedFields(), GoalController.DEFAULT_SORT_FIELD),
                GoalController.SORT, "active"))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat((List<?>) e.payload()).extracting(f -> ((FieldError) f).field())
                                .containsExactly("status"));
    }
}
