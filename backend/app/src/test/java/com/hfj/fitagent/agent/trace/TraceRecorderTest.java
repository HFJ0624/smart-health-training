package com.hfj.fitagent.agent.trace;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.tool.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 轨迹与脱敏（docs/agent-v1-design.md §10、§5.7）。
 *
 * <p>两条必须可证明：轨迹写失败不改变 run 结果（更不触发重发），任何形似密钥的值都到不了 sink。
 */
class TraceRecorderTest {

    @Test
    @DisplayName("sink 抛异常被吞掉：轨迹是可丢的诊断材料，不是计费依据")
    void sinkFailuresAreSwallowed() {
        List<AgentEvent> kept = new ArrayList<>();
        TraceRecorder recorder = TraceRecorder.forRun(event -> {
            kept.add(event);
            throw new IllegalStateException("agent_step 写入失败");
        }, AgentTestSupport.clock());

        assertThatCode(() -> recorder.record(TraceEventType.MODEL_CALL, null, null, null, "一次调用",
                "SUCCEEDED", 12L, null)).doesNotThrowAnyException();

        assertThat(kept).hasSize(1);
        assertThat(recorder.events()).hasSize(1);
    }

    @Test
    @DisplayName("步骤号从 1 起严格递增，事件只读且追加")
    void stepNumbersAreMonotonicAndListIsAppendOnly() {
        TraceRecorder recorder = TraceRecorder.inMemory();
        recorder.record(TraceEventType.MODEL_CALL, null, null, null, "a", "SUCCEEDED", null, null);
        recorder.record(TraceEventType.TOOL_CALL, "get_profile", "c1", "{}", "b", "DECLARED", null, null);
        recorder.record(TraceEventType.ANSWER, null, null, null, "c", "SUCCEEDED", null, null);

        assertThat(recorder.events()).extracting(AgentEvent::stepNo).containsExactly(1, 2, 3);
        assertThat(recorder.lastStepNo()).isEqualTo(3);
    }

    @Test
    @DisplayName("落库时间取注入时钟：轨迹时间与服务端口径一致，不用客户端时间")
    void occurredAtUsesAppClock() {
        TraceRecorder recorder = TraceRecorder.inMemory();
        TraceRecorder fixed = TraceRecorder.forRun(null, AgentTestSupport.clock());

        assertThat(recorder.record(TraceEventType.MODEL_CALL, null, null, null, "x", "SUCCEEDED", null, null)
                .occurredAt()).isAfterOrEqualTo(AgentTestSupport.NOW);
        assertThat(fixed.record(TraceEventType.MODEL_CALL, null, null, null, "x", "SUCCEEDED", null, null)
                .occurredAt()).isEqualTo(AgentTestSupport.NOW);
    }

    @Test
    @DisplayName("密钥形态的值一律被替换，非敏感字段保持可读")
    void redactionStripsKeyLikeValues() {
        String redacted = TraceRedaction.redact("""
                {"days":7,"api_key":"sk-abcdefghijklmnop","authorization":"Bearer sk-abcdefghijklmnop",
                 "userToken":"tok_123456","reasoning_content":"内部思考","password":"p@ss","note":"正常字段"}
                """);

        assertThat(redacted).doesNotContain("sk-abcdefghijklmnop").doesNotContain("tok_123456")
                .doesNotContain("p@ss").doesNotContain("内部思考");
        assertThat(redacted).contains("[已脱敏]");
        assertThat(redacted).contains("正常字段").contains("days");
    }

    @Test
    @DisplayName("非 JSON 文本里的裸 Key 也被遮（模型可能把凭据抄进自由文本）")
    void redactionHandlesFreeText() {
        assertThat(TraceRedaction.redact("我的 key 是 sk-abcdefghijklmnop，请帮我保存"))
                .doesNotContain("sk-abcdefghijklmnop");
        assertThat(TraceRedaction.redact("Bearer sk-abcdefghijklmnop 是这个请求的头"))
                .doesNotContain("Bearer sk-abcdefghijklmnop");
    }

    @Test
    @DisplayName("轨迹文本有长度上限：轨迹不是第二个消息存储")
    void redactionTruncates() {
        String longText = "x".repeat(5000);

        assertThat(TraceRedaction.redact(longText)).hasSizeLessThanOrEqualTo(TraceRedaction.MAX_CHARS + 10);
        assertThat(TraceRedaction.summarize(null, 10)).isNull();
    }

    @Test
    @DisplayName("证据注册表按 run 隔离并保留首次快照")
    void evidenceRegistryKeepsFirstSnapshot() {
        EvidenceRegistry registry = new EvidenceRegistry();
        var data = AgentTestSupport.mapper().readTree("{\"latestWeightKg\":\"71.5\"}");
        var later = AgentTestSupport.mapper().readTree("{\"latestWeightKg\":\"99.9\"}");
        Evidence evidence = AgentTestSupport.evidence("body_measurement", "501");

        assertThat(registry.register(evidence, "get_weight_trend", data, true, AgentTestSupport.DATA_REVISION))
                .isTrue();
        assertThat(registry.register(evidence, "get_weight_trend", later, true, AgentTestSupport.DATA_REVISION))
                .isFalse();
        assertThat(registry.find(evidence.evidenceId()).orElseThrow().dataSnapshot()).isEqualTo(data);
        assertThat(registry.unknownRefs(List.of(evidence.evidenceId(), "ev-missing")))
                .containsExactly("ev-missing");
    }
}
