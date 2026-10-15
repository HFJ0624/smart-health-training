package com.hfj.fitagent.modules.profile.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * profile 模块对外暴露的唯一只读端口：training 需要建立计划时抓取目标/器械/身高快照，
 * 但不允许直接引用 profile 的 Mapper 或 Entity（模块边界与调用方向见 docs/p1-architecture.md §4）。
 *
 * 只有这一个方法，且返回 profile 自己拥有的视图类型：
 * 反向依赖不存在（profile 不知道 training 的存在），auth 也不依赖任何一方。
 */
public interface ProfileContextReader {

    /**
     * @param userId 只能来自认证上下文
     */
    PlanContext readForPlanContext(long userId);

    /**
     * 无档案时各字段为 null，而不是补一个默认身高——缺失参数应当阻止后续公式，不能伪装成已知。
     *
     * @param heightCm            档案身高，可能为 null
     * @param trainingLevel       BEGINNER / INTERMEDIATE / ADVANCED，无档案时为 null
     * @param equipment           可用器械代码，无档案或空值时为空列表
     * @param goalId              当前 ACTIVE 目标 ID，无目标时为 null
     * @param goalType            FAT_LOSS / MUSCLE_GAIN / MAINTAIN / GENERAL_FITNESS，无目标时为 null
     * @param targetWeightKg      目标体重，未设定时为 null
     * @param weeklySessionsTarget 每周目标训练次数，未设定时为 null
     */
    record PlanContext(BigDecimal heightCm,
                       String trainingLevel,
                       List<String> equipment,
                       Long goalId,
                       String goalType,
                       BigDecimal targetWeightKg,
                       Integer weeklySessionsTarget) {

        public static PlanContext empty() {
            return new PlanContext(null, null, List.of(), null, null, null, null);
        }
    }
}
