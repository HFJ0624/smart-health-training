package com.hfj.fitagent.agent.trace;

import java.time.Instant;

/**
 * 一条轨迹事件（对应 agent_step 表的一行，docs/agent-v1-design.md §7 V21）。
 *
 * <p>字段与表列一一对应，由 modules/coach 落库；{@code argumentsRedacted} 已经过 {@link TraceRedaction}
 * 处理，不含任何形似密钥的值。轨迹是<b>可丢的诊断材料</b>，不是计费依据：写失败绝不触发模型调用重发。
 *
 * @param status SUCCEEDED / FAILED / SKIPPED 等，界面按它上色，不解析 summary
 */
public record AgentEvent(int stepNo,
                         String eventType,
                         String toolName,
                         String toolCallId,
                         String argumentsRedacted,
                         String resultSummary,
                         String status,
                         Long durationMs,
                         String errorCode,
                         Instant occurredAt) {

    public AgentEvent {
        if (stepNo <= 0) {
            throw new IllegalArgumentException("stepNo 必须为正数");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("轨迹事件必须有类型");
        }
    }

    /** 界面用的最短摘要：类型 + 状态，不带正文。 */
    @Override
    public String toString() {
        return "AgentEvent[step=" + stepNo + ", type=" + eventType + ", tool=" + toolName
                + ", status=" + status + (errorCode == null ? "" : ", code=" + errorCode) + "]";
    }
}
