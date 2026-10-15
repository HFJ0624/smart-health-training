package com.hfj.fitagent.modules.modelaccess.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 一次成功的余额抓取结果（协议 §8 的响应形状，已按 BigDecimal 绑定）。
 *
 * <p>{@code isAvailable} 是<b>三态</b>：true / false / null（响应里没有该字段）。
 * 旧设计包里的 {@code is_expired} 不存在于当前任何官方文档页，因此这里没有它（协议 §8 更正）。
 *
 * <p>三个金额都可为 null："供应商没给这个分项"与"给了 0"是两件不同的事，
 * 后者才是没钱了；把 null 变成 0 会让页面宣称用户余额为零。
 *
 * @param isAvailable    供应商报告的"余额是否足以继续调用"
 * @param amounts        按币种分开的分项（可能同时出现 CNY 与 USD，永不相减）
 * @param httpStatus     被接受的响应状态（成功路径恒 200）
 * @param sourceErrorCode 供应商在 200 响应内自报的结果码原文；官方示例没有该字段，因此通常为 null
 */
public record BalanceReading(Boolean isAvailable, List<CurrencyAmount> amounts, Integer httpStatus, String sourceErrorCode) {

    public BalanceReading {
        amounts = amounts == null ? List.of() : List.copyOf(amounts);
    }

    /**
     * 单个币种的分项。
     *
     * @param currency  ISO-4217 大写
     * @param total     总余额
     * @param granted   赠送余额（官方扣费顺序是先赠送后充值）
     * @param toppedUp  充值余额
     */
    public record CurrencyAmount(String currency, BigDecimal total, BigDecimal granted, BigDecimal toppedUp) {
    }
}
