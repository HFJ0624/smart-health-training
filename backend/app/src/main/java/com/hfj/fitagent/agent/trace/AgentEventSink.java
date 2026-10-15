package com.hfj.fitagent.agent.trace;

/**
 * 轨迹落库出口，由 {@code modules/coach} 实现（写 agent_step）。
 *
 * <p>Agent 侧不引用 Mapper（design §2 硬规则 1），所以这里只有一个方法，且契约是
 * <b>"抛出的异常会被 TraceRecorder 吞掉"</b>：轨迹是可丢的诊断材料，不是计费依据，
 * 写失败绝不能变成一次模型调用重发（design §10）。
 */
@FunctionalInterface
public interface AgentEventSink {

    void append(AgentEvent event);
}
