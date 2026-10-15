package com.hfj.fitagent.modules.billing.api;

import java.math.BigDecimal;

/**
 * 结算/标记/释放的统一返回值：调用方据此决定"还要不要再发下一次请求"。
 *
 * @param token        操作的凭证
 * @param applied      本次是否真的动了账（false 表示重复回调或已离开 RESERVED，钱没动第二次）
 * @param status       台账当前状态（RESERVED / SETTLED / UNCERTAIN / RELEASED）
 * @param actualCost   已核算金额；未落定时为 null（不是 0）
 * @param currency     币种
 * @param overSpent    落定后是否已超过单次或每日上限（已花出去的钱收不回来，只能如实标记）
 * @param shouldStop   调用方应当停止后续模型调用
 * @param reason       人类可读说明；不含任何凭据材料
 */
public record SettlementResult(ReservationToken token,
                               boolean applied,
                               String status,
                               BigDecimal actualCost,
                               String currency,
                               boolean overSpent,
                               boolean shouldStop,
                               String reason) {

    /** 重复结算：账本没动第二次，但也不能谎称"已停止"。 */
    public static SettlementResult unchanged(ReservationToken token, String status, String currency, String reason) {
        return new SettlementResult(token, false, status, null, currency, false, false, reason);
    }
}
