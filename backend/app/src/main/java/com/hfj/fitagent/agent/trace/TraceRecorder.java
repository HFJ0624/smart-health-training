package com.hfj.fitagent.agent.trace;

import com.hfj.fitagent.common.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次 run 的轨迹记录器：追加式、步骤号单调递增、<b>永不向上抛异常</b>。
 *
 * <p>两条纪律来自 docs/agent-v1-design.md §10：
 * <ul>
 *   <li>轨迹写失败绝不触发模型调用重发——所以 sink 的异常在这里被吞掉并降级为一条 warn 日志；</li>
 *   <li>参数原文在离开记录器前必须脱敏——所以脱敏做在 {@link #record} 内部而不是交给调用方自觉，
 *       少一个"忘记调用"的分支。</li>
 * </ul>
 *
 * <p>非线程安全且刻意如此：一个 run 一个实例，工具顺序执行。
 */
public final class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final AgentEventSink sink;
    private final AppClock clock;
    private final List<AgentEvent> events = new ArrayList<>();
    private int stepNo;

    private TraceRecorder(AgentEventSink sink, AppClock clock) {
        this.sink = sink;
        this.clock = clock;
    }

    public static TraceRecorder forRun(AgentEventSink sink, AppClock clock) {
        return new TraceRecorder(sink, clock);
    }

    /** 只留在内存里（单测与不需要落库的调用方）。 */
    public static TraceRecorder inMemory() {
        return new TraceRecorder(null, null);
    }

    /**
     * @param argumentsRaw 原文（可以含用户输入）；本方法负责脱敏，调用方不需要也不应该预先处理
     * @return 已记录的事件；stepNo 由记录器分配
     */
    public AgentEvent record(String eventType,
                             String toolName,
                             String toolCallId,
                             String argumentsRaw,
                             String resultSummary,
                             String status,
                             Long durationMs,
                             String errorCode) {
        stepNo = stepNo + 1;
        AgentEvent event = new AgentEvent(stepNo, eventType, toolName, toolCallId,
                TraceRedaction.redact(argumentsRaw),
                resultSummary == null ? null : TraceRedaction.summarize(resultSummary, TraceRedaction.MAX_CHARS),
                status, durationMs, errorCode, now());
        events.add(event);
        appendQuietly(event);
        return event;
    }

    /** 只读视图：界面与测试用它核对"每个 tool_call_id 是否都有一条结果"。 */
    public List<AgentEvent> events() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public int lastStepNo() {
        return stepNo;
    }

    private Instant now() {
        return clock == null ? Instant.now() : clock.now();
    }

    private void appendQuietly(AgentEvent event) {
        if (sink == null) {
            return;
        }
        try {
            sink.append(event);
        } catch (Throwable t) {
            // 连 Error 也吞：轨迹落库失败不能改变 run 的结果，也不能引发重发
            log.warn("轨迹落库失败，已忽略（轨迹不是计费依据）step={} type={} errorType={}",
                    event.stepNo(), event.eventType(), t.getClass().getSimpleName());
        }
    }
}
