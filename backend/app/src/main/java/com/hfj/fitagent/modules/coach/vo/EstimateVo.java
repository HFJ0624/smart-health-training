package com.hfj.fitagent.modules.coach.vo;

import java.util.List;

/**
 * {@code POST /api/coach/conversations/{id}/estimate} 的响应（§11.1）。
 *
 * <p>这个接口<b>不调用任何收费模型</b>：全部数字都来自确定性计算（目录报价 + 策略限额 + 已占用账本），
 * 所以它可以被反复点击而不产生费用。也正因如此，这里的每一项假设都必须与执行时真实预占的算法一致
 * ——否则"发送前预估"就成了一个骗人的数字。
 *
 * <p>口径（与 {@code BudgetGate} 同一条路径）：
 * <ul>
 *   <li>输入按<b>未命中</b>价（不假定任何缓存命中）；</li>
 *   <li>输出按 {@code maxOutputTokens} <b>满额</b>；</li>
 *   <li>档位恒取 <b>PEAK</b>（协议 §7：节假日日历未核实，不得假定谷段）。</li>
 * </ul>
 *
 * <p>金额是字符串、不可核算时是 {@code null}（<b>不是</b> "0.0000000000"）：
 * 0 会让一次算不出来的调用看起来免费。
 *
 * @param assumptions        假设清单，页面原样显示；它就是"这个预估凭什么"的答案
 * @param budgetCurrency     预算侧币种；与供应商余额币种是两本账，页面不做汇率折算（§11.1 第 10 条）
 * @param credentialStatus   ACTIVE / MISSING / INVALID / REVOKED
 * @param budgetRemainingTask/Daily 剩余额度；策略未设该维度上限时为 {@code null}（= 不设限，不是 0）
 * @param fakeModel          §12 要求页面显示"模拟模型"标记，本字段必须存在且由配置直接给出
 */
public record EstimateVo(List<String> assumptions,
                         Integer maxModelCalls,
                         Integer maxToolCalls,
                         String estimatedCostLow,
                         String estimatedCostHigh,
                         String currency,
                         String budgetCurrency,
                         List<String> availableTools,
                         String credentialStatus,
                         String budgetRemainingTask,
                         String budgetRemainingDaily,
                         Boolean fakeModel) {
}
