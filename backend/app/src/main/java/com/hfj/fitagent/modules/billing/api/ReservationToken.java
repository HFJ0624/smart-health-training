package com.hfj.fitagent.modules.billing.api;

/**
 * 预占凭证：{@code (userId, reservationId)} 的二元组。
 *
 * <p>刻意做成不透明的小值对象而不是一个裸 long：结算、标记未知、释放三条路径都必须带上用户维度，
 * 这样"拿别人的 reservationId 去动别人的账"在 SQL 层就命中不了行
 * （{@code WHERE id = ? AND user_id = ?}），应用层也忘记不了它——类型里没有单参数的入口。
 *
 * <p>它同时是返回给调用方（未来的执行循环）的句柄，但<b>不</b>是任何 HTTP 接口的入参。
 */
public record ReservationToken(long userId, long reservationId) {

    public ReservationToken {
        if (userId <= 0 || reservationId <= 0) {
            throw new IllegalArgumentException("预占凭证的 userId 与 reservationId 都必须为正");
        }
    }

    @Override
    public String toString() {
        // 不含供应商/金额，够排查用；也不至于变成日志里的敏感聚合对象
        return "ReservationToken{user=" + userId + ", reservation=" + reservationId + "}";
    }
}
