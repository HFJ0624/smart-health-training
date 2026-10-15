package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.modules.training.entity.ExerciseReferenceEntity;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@link PlanAggregateValidator} 的输出：错误、警告，以及**校验通过时**的规范化聚合。
 *
 * <p>规范化聚合是"服务端说了算"的那一份数据：position_no 已按数组顺序重排为 1..n，
 * exercise_code 已解析成字典行（拿到 id/name/category）。落库与预览都只读这里，
 * 不再回头看原始入参，避免"校验的是 A、写的是 B"。
 *
 * @param errors   非空即不可落库（预览层映射为 422 PLAN_IMPORT_INVALID，手工接口映射为 VALIDATION_FAILED）
 * @param warnings 不阻断（目前只有 POSITION_RENUMBERED）
 * @param days     仅当 errors 为空时有值
 */
public record PlanValidationOutcome(List<PlanIssue> errors, List<PlanIssue> warnings, List<ResolvedDay> days) {

    public PlanValidationOutcome {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        days = days == null ? List.of() : List.copyOf(days);
    }

    public static PlanValidationOutcome failed(List<PlanIssue> errors, List<PlanIssue> warnings) {
        return new PlanValidationOutcome(errors, warnings, List.of());
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /**
     * @param isoWeekday 1=周一…7=周日
     * @param items      已按 positionNo 升序
     */
    public record ResolvedDay(int isoWeekday, String title, boolean isRestDay, String notes, List<ResolvedItem> items) {
    }

    /**
     * @param positionNo 服务端规范化后的顺序（1..n）
     * @param exercise   字典行；已通过 status='ACTIVE' 校验
     */
    public record ResolvedItem(int positionNo,
                               ExerciseReferenceEntity exercise,
                               Integer targetSets,
                               Integer repsMin,
                               Integer repsMax,
                               BigDecimal targetExternalLoadKg,
                               Integer targetDurationSeconds,
                               Integer restSeconds,
                               String notes) {
    }
}
