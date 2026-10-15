package com.hfj.fitagent.modules.profile.vo;

import com.hfj.fitagent.modules.profile.entity.UserGoalEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 目标出参。
 *
 * <p>{@code dailyIntakeTargetKcal} 按契约保留字段但恒为 null：本轮页面与接口都不暴露饮食目标
 * （docs/p1-api.md §4.3）。实体侧该列本身是 {@code select = false}，所以连查询都不会取它，
 * 这里写 null 是"没有值"的事实陈述，不是把值藏起来。
 */
public record GoalVo(
        String id,
        String userId,
        String goalType,
        BigDecimal targetWeightKg,
        BigDecimal dailyIntakeTargetKcal,
        Integer weeklySessionsTarget,
        LocalDate startsOn,
        LocalDate endsOn,
        String status,
        Integer lockVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public static GoalVo from(UserGoalEntity e) {
        if (e == null) {
            return null;
        }
        return new GoalVo(
                str(e.getId()),
                str(e.getUserId()),
                e.getGoalType(),
                e.getTargetWeightKg(),
                // 本轮不暴露饮食目标：实体列 select=false，恒 null
                null,
                e.getWeeklySessionsTarget(),
                e.getStartsOn(),
                e.getEndsOn(),
                e.getStatus(),
                e.getLockVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private static String str(Long v) {
        return v == null ? null : Long.toString(v);
    }
}
