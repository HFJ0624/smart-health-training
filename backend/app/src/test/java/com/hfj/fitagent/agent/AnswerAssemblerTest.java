package com.hfj.fitagent.agent;

import com.hfj.fitagent.agent.answer.AgentAnswer;
import com.hfj.fitagent.agent.answer.AnswerAssembler;
import com.hfj.fitagent.agent.answer.Citation;
import com.hfj.fitagent.agent.answer.CitationValidator;
import com.hfj.fitagent.agent.answer.UsageSummary;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.core.StopReason;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引用与事实组装（docs/agent-v1-design.md §9）。
 *
 * <p>要证明的三件事：
 * <ol>
 *   <li>引用不存在于本 run 证据集合 → 丢弃并计入 missingFields，<b>不</b>报错伪装；</li>
 *   <li>事实卡片的值/单位/日期/规则版本只来自工具结果，模型给的数字不一致就被丢弃；</li>
 *   <li>边界诚实：校验只证明"引用存在且归属合法"，不宣称防住了幻觉。</li>
 * </ol>
 */
class AnswerAssemblerTest {

    private final ObjectMapper mapper = AgentTestSupport.mapper();
    private final AnswerAssembler assembler = AgentTestSupport.assembler();
    private final AgentContext context = AgentTestSupport.context(RunLimits.defaults());

    private EvidenceRegistry registryWith(String toolName, JsonNode data, boolean dataComplete) {
        EvidenceRegistry registry = new EvidenceRegistry();
        registry.register(new Evidence("body_measurement", "501", null, AgentTestSupport.BUSINESS_DATE, null,
                AgentTestSupport.DATA_REVISION), toolName, data, dataComplete, AgentTestSupport.DATA_REVISION);
        return registry;
    }

    private String registeredId(EvidenceRegistry registry) {
        return registry.ids().get(0);
    }

    private ModelResponse response(String content) {
        return new ModelResponse(content, List.of(), FinishReason.STOP,
                ModelUsage.of(10L, 90L, 20L, null), "req-1", false);
    }

    private AgentAnswer build(EvidenceRegistry registry, String content) {
        return assembler.validateAndBuild(context, response(content), registry,
                UsageSummary.of(List.of(ModelUsage.of(10L, 90L, 20L, null)), 1, 1),
                StopReason.SUCCEEDED, true, false, false, false);
    }

    @Test
    @DisplayName("引用不存在的 ID：丢弃、计入 missingFields，不把它伪装成错误")
    void inventedCitationIsRejected() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\",\"unit\":\"kg\"}"), true);

        AgentAnswer answer = build(registry,
                "你的体重是 71.5 kg。".concat("\nCITATIONS: ev-0000000000000000, ").concat(registeredId(registry)));

        assertThat(answer.evidenceRefs()).containsExactly(registeredId(registry));
        assertThat(answer.missingFields()).contains("UNKNOWN_CITATION:ev-0000000000000000");
        assertThat(answer.factCards()).hasSize(1);
        assertThat(answer.stopReason()).isEqualTo(StopReason.SUCCEEDED);
    }

    @Test
    @DisplayName("模型自己填的数字与工具值不一致：卡片仍取工具值，模型说法作废")
    void modelSuppliedNumberNeverOverridesToolData() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\",\"unit\":\"kg\"}"), true);
        String id = registeredId(registry);

        AgentAnswer answer = assembler.validateAndBuild(context,
                response("{\"answer\":\"你最近瘦到了 65 kg\",\"citations\":[{\"evidence_id\":\"" + id
                        + "\",\"field\":\"latestWeightKg\",\"value\":\"65\",\"unit\":\"kg\"}]}"),
                registry, UsageSummary.empty(), StopReason.SUCCEEDED, true, false, false, false);

        assertThat(answer.factCards()).hasSize(1);
        assertThat(answer.factCards().get(0).value()).isEqualTo("71.5");
        assertThat(answer.factCards().get(0).unit()).isEqualTo("kg");
        assertThat(answer.factCards().get(0).asOfDate()).isEqualTo(AgentTestSupport.BUSINESS_DATE);
        assertThat(answer.factCards().get(0).dataRevision()).isEqualTo(AgentTestSupport.DATA_REVISION);
        assertThat(answer.missingFields()).anyMatch(entry -> entry.startsWith("VALUE_CONFLICT:" + id));
        // 服务端校正块必须可见（不静默改写模型 prose，但权威数值以工具为准）
        assertThat(answer.answerText()).contains("服务端校正").contains("71.5");
    }

    @Test
    @DisplayName("模型没有引用任何来源：不产出事实卡片，即使正文里写了数字")
    void numbersWithoutCitationHaveNoPathIntoFacts() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\"}"), true);

        AgentAnswer answer = build(registry, "你大概 71.5 kg，完成度 50%。");

        assertThat(answer.factCards()).isEmpty();
        assertThat(answer.evidenceRefs()).isEmpty();
    }

    @Test
    @DisplayName("JSON 信封与 CITATIONS 行两种写法都接受；机器约定行不留在可见正文里")
    void bothEnvelopeStylesParse() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\",\"unit\":\"kg\"}"), true);
        String id = registeredId(registry);

        AgentAnswer fromLine = build(registry, "结论。\nCITATIONS: " + id);
        assertThat(fromLine.evidenceRefs()).containsExactly(id);
        assertThat(fromLine.answerText()).doesNotContain("CITATIONS:");

        AgentAnswer fromEnvelope = build(registry,
                "{\"answer\":\"结论。\",\"citations\":[{\"evidence_id\":\"" + id + "\"}]}");
        assertThat(fromEnvelope.evidenceRefs()).containsExactly(id);
        assertThat(fromEnvelope.answerText()).isEqualTo("结论。");

        AgentAnswer noneClaimed = build(registry, "结论。\nCITATIONS: NONE");
        assertThat(noneClaimed.evidenceRefs()).isEmpty();
    }

    @Test
    @DisplayName("字段缺失时按整份快照出紧凑摘要，值仍全部来自工具")
    void summaryValueComesFromToolSnapshot() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\",\"history\":[\"72.0\",\"71.5\"]}"), true);

        AgentAnswer answer = build(registry, "结论。CITATIONS: " + registeredId(registry));

        assertThat(answer.factCards().get(0).value()).isEqualTo("latestWeightKg=71.5");
    }

    @Test
    @DisplayName("读取不完整会显式落到 missingFields，不被回答文本盖过去")
    void incompleteReadIsReported() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\"}"), false);

        AgentAnswer answer = build(registry, "结论。CITATIONS: " + registeredId(registry));

        assertThat(answer.missingFields()).anyMatch(entry -> entry.startsWith("INCOMPLETE_READ:"));
    }

    @Test
    @DisplayName("停止路径：确定性模板列出已核实事实与停止原因，不宣称成功")
    void stoppedAnswerUsesDeterministicTemplate() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\",\"unit\":\"kg\"}"), true);

        AgentAnswer answer = assembler.stoppedAnswer(context, registry, UsageSummary.of(List.of(), 1, 1),
                StopReason.BUDGET_STOPPED, false, true, false, false, "今日预算已用完");

        assertThat(answer.runStatus()).isEqualTo("BUDGET_STOPPED");
        assertThat(answer.stopReason()).isEqualTo(StopReason.BUDGET_STOPPED);
        assertThat(answer.factCards()).hasSize(1);
        assertThat(answer.answerText())
                .contains("本轮没有生成自由文本结论")
                .contains("BUDGET_STOPPED")
                .contains("71.5")
                .contains("预算");
        assertThat(answer.missingFields()).contains("ANSWER_NOT_GENERATED:BUDGET_STOPPED");
        assertThat(answer.costUncertain()).isTrue();
    }

    @Test
    @DisplayName("停止路径没有任何事实时如实说明，不用 0 或估算填补")
    void stoppedAnswerWithoutFactsSaysSo() {
        AgentAnswer answer = assembler.stoppedAnswer(context, new EvidenceRegistry(), UsageSummary.empty(),
                StopReason.PROVIDER_ERROR, false, true, false, false, "供应商不可用");

        assertThat(answer.factCards()).isEmpty();
        assertThat(answer.answerText()).contains("没有已核实的事实");
        assertThat(answer.answerText()).doesNotContain("0 kg");
    }

    @Test
    @DisplayName("引用校验器只判断存在性与去重：不判断语义正确（诚实边界）")
    void validatorOnlyChecksMembership() {
        EvidenceRegistry registry = registryWith("get_weight_trend",
                mapper.readTree("{\"latestWeightKg\":\"71.5\",\"targetWeightKg\":\"68\"}"), true);
        String id = registeredId(registry);

        CitationValidator.ValidationResult result = new CitationValidator().validate(List.of(
                new Citation(id, null, "latestWeightKg", null, null),
                new Citation(id, null, "targetWeightKg", null, null),
                new Citation(id, null, "latestWeightKg", null, null),
                new Citation("ev-deadbeefdeadbeef", null, null, null, null)), registry);

        // 同一来源支撑不同字段合法；完全重复才算重复引用
        assertThat(result.accepted()).hasSize(2);
        assertThat(result.duplicates()).isEqualTo(1);
        assertThat(result.rejected()).containsExactly("ev-deadbeefdeadbeef");
    }

    @Test
    @DisplayName("跨 run 的 evidenceId 猜不进来：注册表是 per-run 的")
    void registryIsScopedPerRun() {
        EvidenceRegistry first = registryWith("get_weight_trend", mapper.readTree("{\"a\":1}"), true);
        EvidenceRegistry second = new EvidenceRegistry();

        assertThat(first.contains(registeredId(first))).isTrue();
        assertThat(second.contains(registeredId(first))).isFalse();
    }
}
