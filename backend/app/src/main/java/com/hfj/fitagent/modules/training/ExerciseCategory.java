package com.hfj.fitagent.modules.training;

/**
 * exercise_reference.category。它不是展示标签，而是条目字段校验矩阵的输入
 * （docs/p1-plan-import.md §5）：矩阵的 category 一律由后端从字典查得，绝不由输入文件声明。
 */
public enum ExerciseCategory {

    STRENGTH,
    CARDIO,
    MOBILITY;

    /** 严格解析大写字面量；未知返回 null。 */
    public static ExerciseCategory parseStrict(String raw) {
        if (raw == null) {
            return null;
        }
        for (ExerciseCategory category : values()) {
            if (category.name().equals(raw)) {
                return category;
            }
        }
        return null;
    }
}
