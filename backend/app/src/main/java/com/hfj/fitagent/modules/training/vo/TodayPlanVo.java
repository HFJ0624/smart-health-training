package com.hfj.fitagent.modules.training.vo;

import java.time.LocalDate;

/**
 * 「今天的计划」（{@code GET /api/plans/today}，首页卡片，docs/p1-api.md §7）。
 *
 * <p>三种形态必须可区分，否则首页会显示错误内容：
 * <ol>
 *   <li>没有 ACTIVE 计划 → {@code activePlanId} 为 null，页面显示"未设置计划"；</li>
 *   <li>有 ACTIVE 计划但今天不安排（该星期没有计划日，或 {@code endsOn} 已过）→
 *       {@code activePlanId} 有值而 {@code day} 为 null，页面显示"今天休息/计划已到期"；</li>
 *   <li>正常 → {@code day} 给出当天条目；{@code day.isRestDay=true} 时 items 为空。</li>
 * </ol>
 *
 * <p>{@code businessDate} 与 {@code isoWeekday} 由服务端按 {@code user_account.timezone} 推导，
 * 客户端不传日期：否则补录/改机时就能伪造"今天"。
 */
public record TodayPlanVo(String activePlanId,
                          String planGroupUuid,
                          Integer revisionNo,
                          Integer isoWeekday,
                          LocalDate businessDate,
                          PlanDayVo day) {
}
