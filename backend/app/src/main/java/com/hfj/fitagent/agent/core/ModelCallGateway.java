package com.hfj.fitagent.agent.core;

/**
 * "过预算闸门 + 发远程 + 结算"的唯一出口。
 *
 * <p>实现方是 {@code modules/billing}（短事务预占 → 提交释放行锁 → 发 HTTP → 幂等结算），
 * 由 {@code agent.core} 在调用点串起来，反向由 Spring 装配（docs/agent-v1-design.md §2 依赖方向）。
 *
 * <p>三条契约，实现方必须满足、调用方可以依赖：
 * <ol>
 *   <li><b>每一次远程调用都过本接口</b>：Runner 没有其它通往 ModelClient 的路径，收尾调用与参数修正调用同样要预占。</li>
 *   <li><b>不得在数据库事务/行锁里等待 HTTP</b>（design §6.3、§14）。</li>
 *   <li><b>失败不得自动重发已送达的请求</b>：只有可证明未送达（{@code requestSent=false}）才允许释放预占；
 *       已送达但结果未知转 UNCERTAIN_HELD。</li>
 * </ol>
 * 本接口不抛受检异常：所有失败都以 {@link ModelCallOutcome} 的结构化状态返回，
 * 因为"调用没成功"是循环的正常分支，不是异常流程。
 */
public interface ModelCallGateway {

    ModelCallOutcome call(ModelCallCommand command);
}
