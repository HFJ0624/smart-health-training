package com.hfj.fitagent.modules.billing.api;

import java.math.BigDecimal;

/**
 * 一次预占所需的<b>全部</b>外部事实：供应商、币种、生效单价与策略限额。
 *
 * <p>为什么由外部注入而不是让 billing 自己去查：设计 §2 的硬规则第 4 条要求
 * {@code modelaccess} 与 {@code billing} 互不依赖（谁也不许碰对方的 Mapper）。
 * 本记录 + {@link BudgetContextProvider} 把这条边界实现成"billing 定义端口，modelaccess 提供实现"，
 * billing 侧只看到 JDK 类型与本包的记录。
 *
 * <p>{@code peakPrices} 是刻意由端口方挑好的 <b>PEAK</b> 档报价：节假日日历未核实（协议 §7），
 * 因此预占永远按峰价，与当前时刻是否真在峰段无关。
 *
 * @param providerId    供应商 id（写 budget_reservation.provider_id）
 * @param providerCode  供应商码（日志与错误信息用，不含材料）
 * @param currency      本次预算的币种；与限额、单价必须同源
 * @param peakPrices    PEAK 档生效单价（保守估算的唯一价格来源）
 * @param maxDailyCost  当日上限，null 表示该维度不设限
 * @param maxTaskCost   单次任务上限，null 表示该维度不设限
 * @param maxModelCalls 单个 run 的模型调用次数上限
 * @param enabled       策略是否生效；false 时 BudgetGate 拒绝发起而不是无视预算
 */
public record BudgetContext(long providerId,
                            String providerCode,
                            String currency,
                            UnitPrices peakPrices,
                            BigDecimal maxDailyCost,
                            BigDecimal maxTaskCost,
                            Integer maxModelCalls,
                            boolean enabled) {

    /** 两个上限同时为 NULL 等于"默认放行"，设计 §7 V15 明确禁止这种状态被使用。 */
    public boolean hasAnyLimit() {
        return maxDailyCost != null || maxTaskCost != null;
    }
}
