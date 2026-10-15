package com.hfj.fitagent.modules.training.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 计划聚合的**中性提交模型**：手工编辑（{@link PlanUpsertRequest}）与 JSON 导入
 * （原始文本解析后的结果）都归一到这一个结构，再交给 {@code PlanAggregateValidator}。
 *
 * <p>为什么需要它：校验规则（星期唯一、休息日不含动作、类型×字段矩阵、上限、长度…）
 * 只应该有一份实现。若手工走 DTO、导入走 JsonNode 各写一遍，两边一定会漂移，
 * 而"导入能过、手工保存被打回"这类不一致是最难排查的产品缺陷。
 *
 * <p>字段可为 null 的语义：只会在"上游已保证非空"或"已作为错误上报并短路"时出现，
 * 校验器对 null 一律跳过而不是补默认值（NULL=未知，不当 0）。
 *
 * @param title     计划名
 * @param startsOn  起始业务日
 * @param endsOn    结束业务日，可为 null（长期计划）
 * @param notes     顶层备注；P1 的 training_plan 表没有 notes 列，见校验器与交付说明
 * @param days      计划日，按输入顺序
 */
public record PlanDraftSpec(String title,
                           LocalDate startsOn,
                           LocalDate endsOn,
                           String notes,
                           List<DaySpec> days) {

    /**
     * @param isoWeekday 1=周一…7=周日
     * @param isRestDay  休息日必须 0 个动作
     * @param items      动作条目，按数组顺序（position_no 由服务端按此顺序重排）
     */
    public record DaySpec(Integer isoWeekday,
                          String title,
                          Boolean isRestDay,
                          String notes,
                          List<ItemSpec> items) {
    }

    /**
     * @param exerciseCode 已用 {@code Ids.canonicalExerciseCode} 规范化为大写
     * @param positionNo   客户端给定的顺序，可为 null（缺省按数组顺序）
     */
    public record ItemSpec(String exerciseCode,
                           Integer positionNo,
                           Integer targetSets,
                           Integer repsMin,
                           Integer repsMax,
                           BigDecimal targetExternalLoadKg,
                           Integer targetDurationSeconds,
                           Integer restSeconds,
                           String notes) {
    }
}
