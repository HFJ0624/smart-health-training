package com.hfj.fitagent.modules.profile.vo;

import com.hfj.fitagent.modules.profile.entity.UserProfileEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 档案出参。数据库 Entity 不外泄（docs/p1-architecture.md §4 规则 2），这里只定义契约字段。
 *
 * <p>64 位标识一律以字符串输出，避免 JS Number 精度丢失；{@code lockVersion} 是 INT，保持数字，
 * 与 docs/p1-api.md §4.2 的示例一致。
 *
 * <p>{@code activityCategory} / {@code dietPreferences} / {@code professionalGuidanceRequired}
 * 只读不写：本轮没有消费方，所以不开放录入，但已有的值仍如实回显，不藏数据。
 */
public record ProfileVo(
        String id,
        String userId,
        LocalDate birthDate,
        String sexForEquation,
        BigDecimal heightCm,
        String trainingLevel,
        String activityCategory,
        Instant activityConfirmedAt,
        List<String> dietPreferences,
        List<String> equipment,
        List<String> trainingConstraints,
        Boolean professionalGuidanceRequired,
        Integer lockVersion,
        Instant createdAt,
        Instant updatedAt
) {

    public static ProfileVo from(UserProfileEntity e) {
        if (e == null) {
            return null;
        }
        return new ProfileVo(
                str(e.getId()),
                str(e.getUserId()),
                e.getBirthDate(),
                e.getSexForEquation(),
                e.getHeightCm(),
                e.getTrainingLevel(),
                e.getActivityCategory(),
                e.getActivityConfirmedAt(),
                e.getDietPreferences() == null ? null : List.copyOf(e.getDietPreferences()),
                e.getEquipment() == null ? null : List.copyOf(e.getEquipment()),
                e.getTrainingConstraints() == null ? null : List.copyOf(e.getTrainingConstraints()),
                e.getProfessionalGuidanceRequired(),
                e.getLockVersion(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }

    private static String str(Long v) {
        return v == null ? null : Long.toString(v);
    }
}
