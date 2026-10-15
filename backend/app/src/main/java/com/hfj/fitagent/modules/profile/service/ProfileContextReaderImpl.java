package com.hfj.fitagent.modules.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.modules.profile.api.ProfileContextReader;
import com.hfj.fitagent.modules.profile.dto.Vocabulary;
import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;
import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;
import com.hfj.fitagent.modules.profile.mapper.UserGoalMapper;
import com.hfj.fitagent.modules.profile.mapper.UserProfileMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * profile 对 training 暴露的唯一只读端口实现（docs/p1-architecture.md §4 规则 3）。
 *
 * <p>刻意缺失而不是补造：没有档案就没有身高、没有 ACTIVE 目标就没有 goalType，
 * 缺失的参数应当阻止后续公式，不能伪装成已知（{@link ProfileContextReader.PlanContext} 的契约）。
 * equipment 是唯一例外——空档案语义上就是"没有可用器械"，给空列表而不是 null，
 * 避免调用方为"没查"与"查了是空"再分一套逻辑。
 */
@Service
public class ProfileContextReaderImpl implements ProfileContextReader {

    private final UserProfileMapper profileMapper;
    private final UserGoalMapper goalMapper;

    public ProfileContextReaderImpl(UserProfileMapper profileMapper, UserGoalMapper goalMapper) {
        this.profileMapper = profileMapper;
        this.goalMapper = goalMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public PlanContext readForPlanContext(long userId) {
        // 一律 QueryWrapper：列名来自本类常量而非输入，且不需要 MyBatis 的 lambda→列名缓存
        UserProfileEntity profile = profileMapper.selectOne(
                new QueryWrapper<UserProfileEntity>().eq("user_id", userId));

        // ACTIVE 目标的唯一读法：findActiveId 走 status='ACTIVE'，再由 (id,user_id) 复合条件取实体，
        // 与 UNIQUE(active_user_id) 的"至多一条"语义一致，不需要在应用侧再去重
        Long activeGoalId = goalMapper.findActiveId(userId);
        UserGoalEntity goal = activeGoalId == null ? null : goalMapper.selectOne(
                new QueryWrapper<UserGoalEntity>()
                        .eq("id", activeGoalId)
                        .eq("user_id", userId));

        if (profile == null && goal == null) {
            return PlanContext.empty();
        }
        return new PlanContext(
                profile == null ? null : profile.getHeightCm(),
                profile == null ? null : profile.getTrainingLevel(),
                equipmentOrEmpty(profile),
                goal == null ? null : goal.getId(),
                goal == null ? null : goal.getGoalType(),
                goal == null ? null : goal.getTargetWeightKg(),
                goal == null ? null : goal.getWeeklySessionsTarget());
    }

    private static List<String> equipmentOrEmpty(UserProfileEntity profile) {
        if (profile == null || profile.getEquipment() == null || profile.getEquipment().isEmpty()) {
            return List.of();
        }
        // 只透出词表内的代码：库里可能存有历史脏值或后续新增但未同步词表的值，
        // 快照宁缺毋伪，不把无法解释的代码当成可用器械
        List<String> known = profile.getEquipment().stream()
                .filter(Vocabulary.EQUIPMENT::contains)
                .toList();
        return List.copyOf(known);
    }
}
