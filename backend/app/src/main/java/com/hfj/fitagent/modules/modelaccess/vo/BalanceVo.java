package com.hfj.fitagent.modules.modelaccess.vo;

import java.time.Instant;
import java.util.List;

/**
 * 余额读取形状（{@code GET /api/model-credentials/{providerCode}/balance}）。
 *
 * <p>三态 {@code state} 是这块接口最重要的语义：
 * <ul>
 *   <li>{@code FRESH} —— 本次外呼成功并已落新快照；</li>
 *   <li>{@code STALE} —— 本次外呼失败，展示<b>上一次成功值</b>，{@code stale=true} 且带原 {@code fetchedAt}；</li>
 *   <li>{@code UNAVAILABLE} —— 从来没成功过，{@code items} 为空。</li>
 * </ul>
 * 三种情况下都不会出现"用 0 冒充余额"（协议 §8 的硬性要求）。
 *
 * <p>{@code isAvailable} 是三态布尔（true/false/null）：null 表示供应商响应里没有该字段，
 * 与 false（"余额不足以继续调用"）不同。官方没有 {@code is_expired} 字段，
 * "陈旧"是这里由 {@code fetchedAt} 推导的显示标记，不是库存列。
 *
 * @param providerCode 供应商码
 * @param state        FRESH / STALE / UNAVAILABLE
 * @param stale        是否陈旧（state=STALE 时恒 true）
 * @param fetchedAt    所展示数据的抓取时刻；UNAVAILABLE 时为 null
 * @param isAvailable  供应商报告的"余额是否足以继续调用"
 * @param items        按币种分开的分项
 * @param note         人类可读说明（例如"本次查询失败，展示上次结果"）；不含任何凭据材料
 */
public record BalanceVo(String providerCode,
                        String state,
                        boolean stale,
                        Instant fetchedAt,
                        Boolean isAvailable,
                        List<BalanceItemVo> items,
                        String note) {

    public static final String STATE_FRESH = "FRESH";
    public static final String STATE_STALE = "STALE";
    public static final String STATE_UNAVAILABLE = "UNAVAILABLE";

    public BalanceVo {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static BalanceVo unavailable(String providerCode, String note) {
        return new BalanceVo(providerCode, STATE_UNAVAILABLE, true, null, null, List.of(), note);
    }
}
