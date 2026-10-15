package com.hfj.fitagent.modules.training.vo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 导入预览（校验通过后的规范化结果，docs/p1-plan-import.md §10）。
 *
 * <p>预览返回的是<b>服务端规范化后</b>的数据，而不是输入原文的回显：
 * {@code positionNo} 已按数组顺序重排为 1..n，{@code exerciseCode} 已解析成字典里的
 * 名称与类别，日期已是 ISO 业务日。用户在预览里看到什么，确认后就存什么。
 *
 * <p>preview 无任何副作用：不落库、不占用幂等键，可以反复调用。
 *
 * @param notes        顶层备注。<b>P1 的 training_plan 表没有 notes 列</b>，
 *                     因此它只出现在预览里、不会入库（列补齐见交付说明的待确认项）
 * @param warnings     非阻断提示，例如 POSITION_RENUMBERED
 * @param warningCount 冗余但必要：页面徽标直接取这个数，不必自己数数组
 */
public record ImportPreviewVo(String title,
                              LocalDate startsOn,
                              LocalDate endsOn,
                              String notes,
                              List<Day> days,
                              List<ImportIssueVo> warnings,
                              int warningCount) {

    public ImportPreviewVo {
        days = days == null ? List.of() : List.copyOf(days);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * @param weekdayLabel 周一…周日的中文名，由 isoWeekday 推导，不由输入声明
     * @param items        休息日恒为空数组
     */
    public record Day(Integer isoWeekday,
                      String weekdayLabel,
                      String title,
                      Boolean isRestDay,
                      String notes,
                      List<Item> items) {

        public Day {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /**
     * @param exerciseName 字典里的名称，让预览可读（用户写的是代码）
     * @param category     字典里的类型：它决定这一行该显示组次还是时长
     */
    public record Item(Integer positionNo,
                       String exerciseCode,
                       String exerciseName,
                       String category,
                       Integer targetSets,
                       Integer repsMin,
                       Integer repsMax,
                       BigDecimal targetExternalLoadKg,
                       Integer targetDurationSeconds,
                       Integer restSeconds,
                       String notes) {
    }
}
