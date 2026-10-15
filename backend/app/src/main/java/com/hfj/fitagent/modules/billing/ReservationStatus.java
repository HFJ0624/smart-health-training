package com.hfj.fitagent.modules.billing;

import java.util.Set;

/**
 * {@code budget_reservation.status} 的状态机字面量：{@code RESERVED → SETTLED / UNCERTAIN / RELEASED}。
 *
 * <p>终态只有一个入口：任何转移都必须以"当前是 RESERVED"为条件（mapper 里的比较并交换），
 * 因此重复回调影响 0 行，账本不会动第二次。
 */
public final class ReservationStatus {

    public static final String RESERVED = "RESERVED";
    public static final String SETTLED = "SETTLED";
    public static final String UNCERTAIN = "UNCERTAIN";
    public static final String RELEASED = "RELEASED";

    public static final Set<String> ALLOWED = Set.of(RESERVED, SETTLED, UNCERTAIN, RELEASED);

    /** 已离开 RESERVED 的行都是终态（本模块没有任何"回退"路径）。 */
    public static boolean isTerminal(String status) {
        return SETTLED.equals(status) || UNCERTAIN.equals(status) || RELEASED.equals(status);
    }

    private ReservationStatus() {
    }
}
