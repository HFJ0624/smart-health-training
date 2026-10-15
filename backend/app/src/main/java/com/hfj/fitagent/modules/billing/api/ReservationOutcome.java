package com.hfj.fitagent.modules.billing.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 预占结果：调用方拿到的只有"占到了多少、凭证是什么"，没有任何凭据材料。
 *
 * @param token         结算/释放凭证
 * @param providerId    供应商
 * @param currency      币种（与后续结算必须一致）
 * @param budgetDate    本次调用归属的业务日（按 Asia/Shanghai 从调用开始时刻推导）
 * @param attemptNo     该 run 内第几次调用
 * @param estimatedCost 保守估算额（PEAK 档、无缓存命中、输出按 maxOutputTokens 全价）
 * @param reused        true 表示同一 (runId, attemptNo) 已有台账，本次没有重复占款
 */
public record ReservationOutcome(ReservationToken token,
                                 long providerId,
                                 String currency,
                                 LocalDate budgetDate,
                                 int attemptNo,
                                 BigDecimal estimatedCost,
                                 boolean reused) {
}
