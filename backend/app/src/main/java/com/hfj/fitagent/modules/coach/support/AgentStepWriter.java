package com.hfj.fitagent.modules.coach.support;

import com.hfj.fitagent.agent.trace.AgentEvent;
import com.hfj.fitagent.agent.trace.AgentEventSink;
import com.hfj.fitagent.modules.coach.entity.AgentStepEntity;
import com.hfj.fitagent.modules.coach.mapper.AgentStepMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 每个 run 一个的轨迹落库 sink（agent_step）。
 *
 * <p>为什么不注册成单例 bean：{@link AgentEvent#stepNo()} 由 {@code TraceRecorder} 从 1 开始自分配，
 * 而 {@code (run_id, step_no)} 是唯一键。失联 run 恢复成 QUEUED 后重跑时，上一段尝试已经留下的
 * 步骤行会和新的 stepNo 撞车——所以 sink 必须在建 run 时读一次 {@code MAX(step_no)} 作为偏移。
 *
 * <p>写入失败只打日志、不抛异常：轨迹是可丢的诊断材料，绝不能因为轨迹把一次已付费的模型调用拖成重发
 * （docs/agent-v1-design.md §10）。
 */
@Component
public class AgentStepWriter {

    private static final Logger log = LoggerFactory.getLogger(AgentStepWriter.class);
    private static final int MAX_SUMMARY_CHARS = 512;

    private final AgentStepMapper stepMapper;
    private final ObjectMapper objectMapper;

    public AgentStepWriter(AgentStepMapper stepMapper, ObjectMapper objectMapper) {
        this.stepMapper = stepMapper;
        this.objectMapper = objectMapper;
    }

    /** 绑定 run 与归属用户；偏移量在本方法里读一次，之后纯内存计数。 */
    public AgentEventSink forRun(long userId, long runId) {
        int offset = stepMapper.findMaxStepNo(userId, runId);
        return new RunSink(userId, runId, offset);
    }

    private final class RunSink implements AgentEventSink {

        private final long userId;
        private final long runId;
        private final int offset;

        private RunSink(long userId, long runId, int offset) {
            this.userId = userId;
            this.runId = runId;
            this.offset = offset;
        }

        @Override
        public void append(AgentEvent event) {
            try {
                stepMapper.insert(toEntity(event));
            } catch (RuntimeException e) {
                // 连 Error 之外的所有异常都吞：这一条丢的是审计便利，不是钱或事实
                log.warn("轨迹落库失败已忽略 runId={} step={} type={} errorType={}",
                        runId, event.stepNo(), event.eventType(), e.getClass().getSimpleName());
            }
        }

        private AgentStepEntity toEntity(AgentEvent event) {
            AgentStepEntity entity = new AgentStepEntity();
            entity.setUserId(userId);
            entity.setRunId(runId);
            entity.setStepNo(offset + event.stepNo());
            entity.setEventType(event.eventType());
            entity.setToolName(event.toolName());
            entity.setToolCallId(event.toolCallId());
            entity.setArgumentsRedacted(arguments(event.argumentsRedacted()));
            entity.setResultSummary(summarize(event.resultSummary()));
            entity.setStatus(event.status());
            entity.setDurationMs(event.durationMs());
            entity.setErrorCode(event.errorCode());
            return entity;
        }
    }

    /** TraceRedaction 已经把值换成掩码；这里只保证 JSON 列放得下且仍是对象。 */
    private Map<String, Object> arguments(String redacted) {
        if (redacted == null || redacted.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> parsed = objectMapper.readValue(redacted, Map.class);
            Map<String, Object> out = new LinkedHashMap<>();
            parsed.forEach((key, value) -> out.put(String.valueOf(key), value));
            return out;
        } catch (RuntimeException e) {
            // 截断到 512 字符后不是合法 JSON 是预期情况，不能因此丢整条轨迹
            return Map.of("redacted", redacted);
        }
    }

    private static String summarize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() <= MAX_SUMMARY_CHARS ? raw : raw.substring(0, MAX_SUMMARY_CHARS);
    }
}
