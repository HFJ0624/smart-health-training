package com.hfj.fitagent.modules.coach.service;

/**
 * 提交与执行之间的派发端口（docs/agent-v1-design.md §10）。
 *
 * <p>接口存在的理由有两个：
 * <ol>
 *   <li>{@code CoachApplicationService} 不该知道线程池、租约与恢复扫描的存在，它只负责
 *       "事务提交之后再叫人去跑"——在事务内派发会让 Worker 读到尚未提交的 run；</li>
 *   <li>测试可以换一个空实现进来，验证"提交只落库、不发收费请求"，而不必真的起线程。</li>
 * </ol>
 */
public interface RunDispatcher {

    /** 当前事务提交后派发；没有事务在跑就直接派发。 */
    void scheduleAfterCommit(long runId);

    /** 立刻派发（幂等提交命中已有 QUEUED run 时用，避免用户重发同一问题后干等补偿扫描）。 */
    void scheduleNow(long runId);

    /** 把取消意图传给正在执行的 Worker：只影响在途调用，已发出的请求不免费。 */
    void notifyCancel(long runId);
}
