package com.hfj.fitagent.modules.billing.api;

/**
 * 预算上下文的只读端口，由 {@code modules/modelaccess} 实现（Spring 反向装配）。
 *
 * <p>方向说明：端口归消费方（billing）所有，实现方（modelaccess）依赖这个接口，
 * billing 因此<b>不</b>引用 modelaccess 的任何类或 Mapper——这是设计 §2 里"两个模块互不依赖、
 * 由调用点串起来"在只有一个调用方时的最小实现。
 *
 * <p>实现方的失败语义必须是 {@code BizException}：拿不到生效报价/策略缺失都是"不能开始花钱"，
 * 不允许返回一个"看起来能用"的零值上下文。
 */
public interface BudgetContextProvider {

    /**
     * 解析该用户当前唯一在用的供应商上下文（单供应商部署下的便捷入口）。
     * 无法唯一确定时必须抛错，不允许随便挑一个供应商。
     */
    BudgetContext contextFor(long userId);

    /** 解析该用户在指定供应商上的上下文（币种与档位由实现方按生效报价确定）。 */
    BudgetContext contextFor(long userId, long providerId);
}
