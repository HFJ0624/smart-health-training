package com.hfj.fitagent.modules.training.vo;

import java.math.BigDecimal;

/**
 * 计划日内的动作条目（docs/p1-api.md §7 聚合的 items[] 元素）。
 *
 * <p>字典字段（exerciseCode / exerciseName / category）是读取时按 {@code exerciseId}
 * 从字典解析出来的：条目表里只有 id，页面与导入预览都需要代码、名称和类型
 * （类型决定这一行该显示组次还是时长）。
 *
 * <p>可空字段的语义严格区分：null = 未设定，0 = 明确设为 0（外部负重 0 表示自重），
 * 不做默认填补（docs/p1-database.md §3.9）。
 */
public record PlanItemVo(String id,
                         String exerciseId,
                         String exerciseCode,
                         String exerciseName,
                         String category,
                         Integer positionNo,
                         Integer targetSets,
                         Integer repsMin,
                         Integer repsMax,
                         BigDecimal targetExternalLoadKg,
                         Integer targetDurationSeconds,
                         Integer restSeconds,
                         String notes) {
}
