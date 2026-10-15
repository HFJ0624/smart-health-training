package com.hfj.fitagent.modules.training.vo;

import java.util.List;

/**
 * 每周计划日（{@code training_plan_day}）。
 *
 * <p>{@code isoWeekday} 用 ISO 口径 1=周一…7=周日，与数据库 CHECK 和导入模板一致；
 * 页面若要显示"周日在前"自行换算，服务端不引入第二种星期口径。
 *
 * <p>休息日 {@code items} 恒为空数组（不是 null）：Service 层禁止休息日带动作，
 * 前端据此渲染"休息"卡片。
 *
 * <p>{@code planId} 一并给出：day 会被单独摊平返回（{@code GET /api/plans/today}），
 * 页面此时仍需知道它属于哪个计划版本才能跳详情。
 */
public record PlanDayVo(String id,
                        String planId,
                        Integer isoWeekday,
                        String title,
                        Boolean isRestDay,
                        String notes,
                        List<PlanItemVo> items) {
}
