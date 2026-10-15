package com.hfj.fitagent.modules.modelaccess.vo;

import java.math.BigDecimal;

/**
 * 单个币种的余额分项（协议 §8：官方可能同时返回 CNY 与 USD）。
 *
 * <p>金额可空：null 是"供应商没给这个分项"，0 是"确实为 0"，两者在界面上必须不同（V14 注释）。
 * 不同币种是分开的两行，前端分开显示，后端永不相减、不猜汇率。
 */
public record BalanceItemVo(String currency,
                            BigDecimal totalBalance,
                            BigDecimal grantedBalance,
                            BigDecimal toppedUpBalance) {
}
