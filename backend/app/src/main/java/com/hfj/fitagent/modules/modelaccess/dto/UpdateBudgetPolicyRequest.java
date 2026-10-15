package com.hfj.fitagent.modules.modelaccess.dto;

import java.math.BigDecimal;

/**
 * 预算策略写入请求（{@code PUT /api/budgets/{providerCode}}）。
 *
 * <p>字段语义与列注释一致，不接受任何"隐式默认"：
 * <ul>
 *   <li>{@code maxTaskCost} / {@code maxDailyCost}：正数或 null（null = 该维度不设限，<b>不是</b> 0）；</li>
 *   <li>{@code currency}：可空。已有策略时忽略（币种是身份的一部分，不可就地改）；
 *       新建时为空则要求后端能从生效报价唯一确定币种，否则报字段错误——不猜；</li>
 *   <li>{@code enabled}：null 表示不改动（新建时按列默认 TRUE）；关闭不等于放行，
 *       而是 BudgetGate 直接拒绝发起调用；</li>
 *   <li>{@code lockVersion}：已存在策略时必填，乐观锁。</li>
 * </ul>
 *
 * <p>{@code budgetTimezone} 不在这里：本轮固定 Asia/Shanghai，没有任何写入路径（设计 §7 V15）。
 */
public record UpdateBudgetPolicyRequest(String currency,
                                        BigDecimal maxTaskCost,
                                        BigDecimal maxDailyCost,
                                        Integer maxModelCalls,
                                        Integer maxToolCalls,
                                        Integer maxOutputTokens,
                                        Boolean enabled,
                                        Integer lockVersion) {
}
