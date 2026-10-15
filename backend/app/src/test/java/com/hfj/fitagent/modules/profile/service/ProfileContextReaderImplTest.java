package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.modules.profile.api.ProfileContextReader;
import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;
import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;
import com.hfj.fitagent.modules.profile.mapper.UserGoalMapper;
import com.hfj.fitagent.modules.profile.mapper.UserProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * training 侧唯一能拿到的 profile 快照。核心断言只有一条：**缺的就是 null，不许补造**。
 * 伪造一个 170cm 或"GENERAL_FITNESS"会让后续公式看起来有据，实际上是凭空捏造。
 */
class ProfileContextReaderImplTest {

    private static final long USER = 7L;

    private UserProfileMapper profileMapper;
    private UserGoalMapper goalMapper;
    private ProfileContextReaderImpl reader;

    @BeforeEach
    void setUp() {
        profileMapper = mock(UserProfileMapper.class);
        goalMapper = mock(UserGoalMapper.class);
        reader = new ProfileContextReaderImpl(profileMapper, goalMapper);
    }

    private static UserProfileEntity profile(BigDecimal heightCm, String level, List<String> equipment) {
        UserProfileEntity entity = new UserProfileEntity();
        entity.setId(1L);
        entity.setUserId(USER);
        entity.setHeightCm(heightCm);
        entity.setTrainingLevel(level);
        entity.setEquipment(equipment);
        return entity;
    }

    private static UserGoalEntity activeGoal() {
        UserGoalEntity goal = new UserGoalEntity();
        goal.setId(21L);
        goal.setUserId(USER);
        goal.setGoalType("FAT_LOSS");
        goal.setTargetWeightKg(new BigDecimal("70.00"));
        goal.setWeeklySessionsTarget(4);
        goal.setStatus("ACTIVE");
        return goal;
    }

    @Test
    @DisplayName("无档案且无目标 → 空快照，各字段 null / 空列表，不补造身高与目标类型")
    void emptyWhenNothingExists() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.findActiveId(USER)).thenReturn(null);

        assertThat(reader.readForPlanContext(USER))
                .isEqualTo(ProfileContextReader.PlanContext.empty());
    }

    @Test
    @DisplayName("有档案无目标 → 身高/经验/器械可用，goal 相关字段全为 null")
    void profileWithoutGoalLeavesGoalFieldsNull() {
        when(profileMapper.selectOne(any())).thenReturn(
                profile(new BigDecimal("175.00"), "BEGINNER", List.of("BARBELL", "MAT")));
        when(goalMapper.findActiveId(USER)).thenReturn(null);

        PlanContextHolder ctx = holder(reader.readForPlanContext(USER));
        assertThat(ctx.heightCm()).isEqualByComparingTo("175.00");
        assertThat(ctx.trainingLevel()).isEqualTo("BEGINNER");
        assertThat(ctx.equipment()).containsExactly("BARBELL", "MAT");
        assertThat(ctx.goalId()).isNull();
        assertThat(ctx.goalType()).isNull();
        assertThat(ctx.targetWeightKg()).isNull();
        assertThat(ctx.weeklySessionsTarget()).isNull();
        // 没有 ACTIVE 目标就不该去取目标行
        verify(goalMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("有目标无档案 → 目标字段可用，身高仍为 null（不拿目标反推身高）")
    void goalWithoutProfileLeavesHeightNull() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.findActiveId(USER)).thenReturn(21L);
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());

        PlanContextHolder ctx = holder(reader.readForPlanContext(USER));
        assertThat(ctx.heightCm()).isNull();
        assertThat(ctx.trainingLevel()).isNull();
        assertThat(ctx.equipment()).isEmpty();
        assertThat(ctx.goalId()).isEqualTo(21L);
        assertThat(ctx.goalType()).isEqualTo("FAT_LOSS");
        assertThat(ctx.targetWeightKg()).isEqualByComparingTo("70.00");
        assertThat(ctx.weeklySessionsTarget()).isEqualTo(4);
    }

    @Test
    @DisplayName("器械数组里的未知代码不透传，避免快照里出现无法解释的值")
    void unknownEquipmentCodesAreDropped() {
        when(profileMapper.selectOne(any())).thenReturn(
                profile(new BigDecimal("175.00"), "BEGINNER", List.of("BARBELL", "MYSTERY_GADGET")));
        when(goalMapper.findActiveId(USER)).thenReturn(null);

        assertThat(holder(reader.readForPlanContext(USER)).equipment()).containsExactly("BARBELL");
    }

    @Test
    @DisplayName("档案与器械列为 null 时是空列表，而不是 null —— 调用方不必再区分'没查'与'查了是空'")
    void nullEquipmentBecomesEmptyList() {
        when(profileMapper.selectOne(any())).thenReturn(profile(new BigDecimal("175.00"), "BEGINNER", null));
        when(goalMapper.findActiveId(USER)).thenReturn(null);

        assertThat(holder(reader.readForPlanContext(USER)).equipment()).isEmpty();
    }

    @Test
    @DisplayName("取档案与取目标都带调用者的 user_id 条件")
    void readsAreAlwaysScopedToTheCaller() {
        when(profileMapper.selectOne(any())).thenReturn(null);
        when(goalMapper.findActiveId(USER)).thenReturn(21L);
        when(goalMapper.selectOne(any())).thenReturn(activeGoal());

        reader.readForPlanContext(USER);

        ArgumentCaptor<QueryWrapper<UserProfileEntity>> profileQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(profileMapper).selectOne(profileQuery.capture());
        // paramNameValuePairs 是在生成 SQL 片段时才回填的，先取一定是空集合
        assertThat(profileQuery.getValue().getSqlSegment()).contains("user_id");
        assertThat(profileQuery.getValue().getParamNameValuePairs().values()).contains(USER);

        ArgumentCaptor<QueryWrapper<UserGoalEntity>> goalQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(goalMapper).selectOne(goalQuery.capture());
        assertThat(goalQuery.getValue().getSqlSegment()).contains("id").contains("user_id");
        assertThat(goalQuery.getValue().getParamNameValuePairs().values()).contains(USER, 21L);
    }

    /**
     * PlanContext 是接口里的 record，字段顺序即语义；这里做一个只读投影，
     * 免得断言里到处写 {@code ctx.f0()} 这种看不出含义的东西。
     */
    private static PlanContextHolder holder(ProfileContextReader.PlanContext ctx) {
        return new PlanContextHolder(ctx.heightCm(), ctx.trainingLevel(), ctx.equipment(), ctx.goalId(),
                ctx.goalType(), ctx.targetWeightKg(), ctx.weeklySessionsTarget());
    }

    private record PlanContextHolder(BigDecimal heightCm, String trainingLevel, List<String> equipment,
                                     Long goalId, String goalType, BigDecimal targetWeightKg,
                                     Integer weeklySessionsTarget) {
    }
}
