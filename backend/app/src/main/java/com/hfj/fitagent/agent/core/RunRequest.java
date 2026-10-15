package com.hfj.fitagent.agent.core;

import com.hfj.fitagent.agent.context.PriorTurn;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import com.hfj.fitagent.agent.trace.TraceRecorder;

import java.util.List;

/**
 * 一次 run 的输入包。
 *
 * <p>{@link TraceRecorder} 与 {@link EvidenceRegistry} 由调用方（modules/coach）<b>每 run 新建</b>并传进来：
 * 轨迹要写 agent_step（那是 coach 的表），证据集合必须 per-run（跨 run 引用别人的 evidenceId 必须失败）。
 *
 * @param history 会话历史，只含 user 与最终可见 assistant 文本（design §8）
 */
public record RunRequest(AgentContext context,
                         String question,
                         List<PriorTurn> history,
                         RunCancellation cancellation,
                         TraceRecorder trace,
                         EvidenceRegistry evidenceRegistry) {

    public RunRequest {
        if (context == null) {
            throw new IllegalArgumentException("RunRequest 需要 AgentContext");
        }
        history = history == null ? List.of() : List.copyOf(history);
        cancellation = cancellation == null ? new RunCancellation() : cancellation;
        trace = trace == null ? TraceRecorder.inMemory() : trace;
        evidenceRegistry = evidenceRegistry == null ? new EvidenceRegistry() : evidenceRegistry;
    }
}
