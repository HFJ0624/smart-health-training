package com.hfj.fitagent.agent.core;

/**
 * 读取用户当前 {@code data_revision} 的只读端口（docs/agent-v1-design.md §9）。
 *
 * <p>Agent 侧不引用任何 Mapper，所以由装配方（modules/coach）用一个走 UserAccount 只读查询的实现注入。
 * Runner 在 run 开始与结束各读一次，不同则把结果标记为陈旧：事实保留原快照与来源日期，
 * <b>不自动付费重跑</b>。
 */
@FunctionalInterface
public interface DataRevisionReader {

    long currentRevision(AgentContext context);

    /** 没有注入实现时使用：不宣称陈旧，也不宣称一致，由 AgentRunner 记为"未核对"。 */
    DataRevisionReader NONE = context -> Long.MIN_VALUE;

    /** {@link #NONE} 的哨兵返回值：表示本轮没有可比对的修订号。 */
    long UNKNOWN = Long.MIN_VALUE;
}
