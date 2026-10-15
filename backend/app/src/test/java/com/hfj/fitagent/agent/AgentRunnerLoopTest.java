package com.hfj.fitagent.agent;

import com.hfj.fitagent.agent.answer.AgentAnswer;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.core.RunRequest;
import com.hfj.fitagent.agent.core.StopReason;
import com.hfj.fitagent.agent.fake.FakeModelClient;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.model.ToolCall;
import com.hfj.fitagent.agent.model.ToolChoice;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import com.hfj.fitagent.agent.trace.TraceRecorder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行循环的行为验收（docs/agent-v1-design.md §4）。全部用 {@link FakeModelClient} + 脚本化网关，
 * 不起 Spring、不连数据库、不发网络。
 */
class AgentRunnerLoopTest {

    private static final RunLimits LIMITS = RunLimits.defaults();

    private static ModelResponse answer(String text) {
        return new ModelResponse(text, List.of(), FinishReason.STOP,
                ModelUsage.of(10L, 90L, 20L, null), "req-answer", false);
    }

    private static ModelResponse tools(ToolCall... calls) {
        return new ModelResponse("", List.of(calls), FinishReason.TOOL_CALLS,
                ModelUsage.of(10L, 90L, 20L, null), "req-" + System.nanoTime(), false);
    }

    private static ToolCall call(String id, String name, String arguments) {
        return new ToolCall(id, name, arguments);
    }

    private static AgentTestSupport.WeightTrendTool weightTool() {
        return new AgentTestSupport.WeightTrendTool();
    }

    // ---- 1. 无工具的知识问题一轮结束 ----

    @Test
    @DisplayName("知识型问题不需要工具：一次调用就结束，且没有 tool 消息混进上下文")
    void knowledgeQuestionFinishesInOneRound() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(answer("热身一般 5–10 分钟。"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        EvidenceRegistry evidence = new EvidenceRegistry();
        AgentContext ctx = AgentTestSupport.context(LIMITS);

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS)
                .run(AgentTestSupport.request(ctx, "一般建议热身多久？", TraceRecorder.inMemory(), evidence,
                        new RunCancellation(), List.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        assertThat(result.runStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.answerText()).contains("热身");
        assertThat(gateway.requests()).hasSize(1);
        // 没有任何工具调用 → 上下文里不允许出现 tool 消息（孤儿 tool 消息就是协议错误）
        assertThat(gateway.requests().get(0).messages())
                .noneMatch(message -> message.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL);
        assertThat(result.factCards()).isEmpty();
        assertThat(result.evidenceRefs()).isEmpty();
    }

    // ---- 2. 个人问题至少两轮，且 assistant/tool 真实配对 ----

    @Test
    @DisplayName("个人事实必须过工具：两次调用、第二轮带着真实 assistant(tool_calls)+tool 配对")
    void personalQuestionRunsAtLeastTwoRoundsWithRealPairing() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(tools(call("call-1", "get_weight_trend", "{\"days\":7}")));
        client.enqueue(answer("最近体重 71.5 kg（ev-来源见引用）。"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        AgentTestSupport.WeightTrendTool tool = weightTool();
        EvidenceRegistry evidence = new EvidenceRegistry();
        AgentContext ctx = AgentTestSupport.context(LIMITS);

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                .run(AgentTestSupport.request(ctx, "我最近体重多少？", TraceRecorder.inMemory(), evidence,
                        new RunCancellation(), List.of()));

        assertThat(gateway.requests()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(tool.calls()).isEqualTo(1);
        assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);

        List<ModelMessage> second = gateway.requests().get(1).messages();
        List<ModelMessage> assistants = second.stream()
                .filter(ModelMessage::isAssistantToolCallTurn).toList();
        List<ModelMessage> toolMessages = second.stream()
                .filter(m -> m.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL).toList();
        assertThat(assistants).hasSize(1);
        assertThat(toolMessages).hasSize(1);
        // 原样回传：assistant 消息里仍带着模型给的 arguments 原文与 id
        assertThat(assistants.get(0).toolCalls()).extracting(ToolCall::id).containsExactly("call-1");
        assertThat(toolMessages.get(0).toolCallId()).isEqualTo("call-1");
        // 工具结果里必须带上 evidenceId，否则模型没有任何可引用的东西
        assertThat(toolMessages.get(0).content()).contains("evidenceId");
        assertThat(evidence.size()).isEqualTo(1);
    }

    // ---- 3. 一轮多个 tool_calls：每个 id 恰好一条 tool 消息 ----

    @Test
    @DisplayName("一轮声明 3 个工具调用（含 1 个本 run 不允许的工具）→ 3 条配对结果，被拒工具得到结构化错误")
    void everyDeclaredToolCallGetsExactlyOneToolMessage() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(tools(
                call("call-a", "get_weight_trend", "{\"days\":7}"),
                call("call-b", "get_profile", "{}"),
                call("call-c", "get_workout_records", "{}")));
        client.enqueue(answer("已经读到了。"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        EvidenceRegistry evidence = new EvidenceRegistry();
        AgentContext ctx = AgentTestSupport.context(LIMITS);

        AgentAnswer result = AgentTestSupport.runner(gateway,
                AgentTestSupport.registry(weightTool(), new AgentTestSupport.ProfileTool()), LIMITS)
                .run(AgentTestSupport.request(ctx, "综合看一下我现在的状态", TraceRecorder.inMemory(), evidence,
                        new RunCancellation(), List.of()));

        ModelRequest second = gateway.requests().get(1);
        List<String> declaredIds = second.messages().stream()
                .filter(ModelMessage::isAssistantToolCallTurn)
                .flatMap(message -> message.toolCalls().stream())
                .map(ToolCall::id)
                .toList();
        List<String> resultIds = second.messages().stream()
                .filter(message -> message.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL)
                .map(ModelMessage::toolCallId)
                .toList();
        assertThat(declaredIds).containsExactly("call-a", "call-b", "call-c");
        assertThat(resultIds).containsExactly("call-a", "call-b", "call-c");
        assertThat(resultIds).doesNotHaveDuplicates();
        // 被拒的工具：不执行，但仍然配对，错误码结构化。
        // 服务端白名单先拦（TOOL_NOT_ALLOWED），注册表再拦（UNKNOWN_TOOL）——见 ToolExecutorTest 两个用例。
        String unknownPayload = second.messages().stream()
                .filter(message -> "call-c".equals(message.toolCallId()))
                .findFirst().orElseThrow().content();
        assertThat(unknownPayload).contains(ToolErrorCode.TOOL_NOT_ALLOWED);
        // 本轮不注册的工具连声明都不该发出去（baseline §3.3）
        assertThat(second.tools()).extracting(com.hfj.fitagent.agent.tool.ToolDefinition::name)
                .doesNotContain("get_workout_records");
        assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
    }

    @Nested
    @DisplayName("工具结果复用与循环检测")
    class ReuseAndRepetition {

        @Test
        @DisplayName("同一轮里两个语义相同的调用只读一次库，但仍然各自得到一条 tool 消息")
        void sameRoundDuplicateReadsReuseResult() {
            FakeModelClient client = AgentTestSupport.fake();
            // 键序不同、数值相同 → 规范化后同一个键
            client.enqueue(tools(
                    call("dup-1", "get_weight_trend", "{\"days\":7}"),
                    call("dup-2", "get_weight_trend", "{\"days\":7}")));
            client.enqueue(answer("好了。"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            AgentTestSupport.WeightTrendTool tool = weightTool();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "重复问同一个读数",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(tool.calls()).isEqualTo(1);
            List<String> resultIds = gateway.requests().get(1).messages().stream()
                    .filter(message -> message.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL)
                    .map(ModelMessage::toolCallId).toList();
            assertThat(resultIds).containsExactly("dup-1", "dup-2");
            assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        }

        @Test
        @DisplayName("连续两轮读同一个键 → REPETITIVE_LOOP 停止，不让模型空转烧预算")
        void consecutiveIdenticalToolReadsStopTheLoop() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("round-1", "get_weight_trend", "{\"days\":7}")));
            client.enqueue(tools(call("round-2", "get_weight_trend", "{\"days\":7}")));
            client.enqueue(tools(call("round-3", "get_weight_trend", "{\"days\":7}")));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            AgentTestSupport.WeightTrendTool tool = weightTool();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "反复问同一件事",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(result.stopReason()).isEqualTo(StopReason.REPETITIVE_LOOP);
            assertThat(tool.calls()).isEqualTo(1);
            // 缓存让第二次没有再读库，但第二次调用仍然发生了 —— 停止时必须已经补齐配对
            assertThat(gateway.requests()).hasSize(2);
        }
    }

    // ---- 4. 截断/损坏的参数一律不执行 ----

    @Nested
    @DisplayName("不完整与非法输入")
    class IncompleteAndInvalid {

        @Test
        @DisplayName("finish_reason=length 时带 tool_calls：arguments 一律不执行")
        void truncatedArgumentsAreNeverExecuted() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(new ModelResponse("", List.of(call("half", "get_weight_trend", "{\"days\":")),
                    FinishReason.LENGTH, ModelUsage.of(null, null, 1024L, null), "req-trunc", true));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            AgentTestSupport.WeightTrendTool tool = weightTool();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "我最近体重多少",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(result.stopReason()).isEqualTo(StopReason.PROTOCOL_INCOMPLETE);
            assertThat(tool.calls()).isZero();
            assertThat(gateway.requests()).hasSize(1);
            assertThat(result.missingFields()).contains("ANSWER_NOT_GENERATED:PROTOCOL_INCOMPLETE");
        }

        @Test
        @DisplayName("aborted / content_filter / insufficient_system_resource 都按不完整处理")
        void otherIncompleteFinishReasonsStop() {
            for (FinishReason reason : List.of(FinishReason.ABORTED, FinishReason.CONTENT_FILTER,
                    FinishReason.INSUFFICIENT_SYSTEM_RESOURCE)) {
                FakeModelClient client = AgentTestSupport.fake();
                client.enqueue(new ModelResponse("半句话", List.of(), reason, ModelUsage.unknown(), "req-x", false));
                AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

                AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS)
                        .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "随便问问",
                                TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

                assertThat(result.stopReason())
                        .as("finish_reason=%s 必须按不完整处理", reason)
                        .isEqualTo(StopReason.PROTOCOL_INCOMPLETE);
                assertThat(gateway.requests()).hasSize(1);
            }
        }

        @Test
        @DisplayName("arguments 非法 JSON → 结构化错误 + 恰好一次修正轮；第二次仍非法就停止")
        void malformedArgumentsAllowOneRepairThenStop() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("bad-1", "get_weight_trend", "{\"days\":")));
            client.enqueue(tools(call("bad-2", "get_weight_trend", "not json")));
            client.enqueue(tools(call("bad-3", "get_weight_trend", "{\"days\":}")));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            AgentTestSupport.WeightTrendTool tool = weightTool();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "我最近体重多少",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(result.stopReason()).isEqualTo(StopReason.PROTOCOL_INCOMPLETE);
            assertThat(tool.calls()).isZero();
            // 一次非法 + 一次修正机会 = 两次调用
            assertThat(gateway.requests()).hasSize(2);
            assertThat(gateway.requests().get(1).messages().stream()
                    .filter(m -> m.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL)
                    .findFirst().orElseThrow().content())
                    .contains(ToolErrorCode.INVALID_ARGUMENTS);
        }

        @Test
        @DisplayName("Bean Validation 不通过（days 非正数）→ VALIDATION_FAILED，工具不执行")
        void beanValidationRejectsBadValues() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("neg", "get_weight_trend", "{\"days\":-3}")));
            client.enqueue(answer("参数不对。"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            AgentTestSupport.WeightTrendTool tool = weightTool();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "最近三天体重",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(tool.calls()).isZero();
            assertThat(gateway.requests().get(1).messages().stream()
                    .filter(m -> m.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL)
                    .findFirst().orElseThrow().content()).contains(ToolErrorCode.VALIDATION_FAILED);
            assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        }

        @Test
        @DisplayName("未知字段被严格解析拒绝（不能靠多余字段影响服务端行为）")
        void unknownFieldInArgumentsIsRejected() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("extra", "get_weight_trend", "{\"days\":7,\"userId\":999}")));
            client.enqueue(answer("不能这样问。"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            AgentTestSupport.WeightTrendTool tool = weightTool();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(tool), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "看看别人的体重",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(tool.calls()).isZero();
            String toolContent = gateway.requests().get(1).messages().stream()
                    .filter(m -> m.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL)
                    .findFirst().orElseThrow().content();
            // userId 既是未知字段，也属于禁止出现在参数里的身份字段
            assertThat(toolContent).containsAnyOf(ToolErrorCode.INVALID_ARGUMENTS,
                    ToolErrorCode.FORBIDDEN_IDENTITY_FIELD);
            assertThat(result.stopReason()).isNotEqualTo(StopReason.PROTOCOL_INCOMPLETE);
        }

        @Test
        @DisplayName("结果超出字节上限：明细丢弃、truncated 与 dataComplete=false 一起说出来")
        void oversizedResultIsCappedAndDeclaredIncomplete() {
            RunLimits tiny = new RunLimits(4, 8, 4, 220, 1024, 120);
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("big", "get_weight_trend", "{\"days\":30}")));
            client.enqueue(answer("数据不全。"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), tiny)
                    .run(AgentTestSupport.request(AgentTestSupport.context(tiny), "最近一个月体重趋势",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            String toolContent = gateway.requests().get(1).messages().stream()
                    .filter(m -> m.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL)
                    .findFirst().orElseThrow().content();
            assertThat(toolContent).contains("\"truncated\":true");
            assertThat(toolContent).contains("\"dataComplete\":false");
            assertThat(toolContent).doesNotContain("72.0");
            // 上限被压到连来源清单都放不下时也必须仍是合法 JSON
            assertThat(toolContent.trim()).startsWith("{");
            assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        }
    }

    // ---- 9. 工程闸门 ----

    @Nested
    @DisplayName("闸门与收尾")
    class Gates {

        @Test
        @DisplayName("模型调用次数耗尽且没有可用回答 → LOOP_EXHAUSTED，并用确定性模板返回已核实事实")
        void modelCallCapEndsRun() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("c1", "get_weight_trend", "{\"days\":7}")));
            client.enqueue(tools(call("c2", "get_profile", "{}")));
            client.enqueue(tools(call("c3", "get_weight_trend", "{\"days\":14}")));
            client.enqueue(tools(call("c4", "get_profile", "{\"days\":1}")));
            client.enqueue(answer("永远不会被读到的一轮"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

            AgentAnswer result = AgentTestSupport.runner(gateway,
                    AgentTestSupport.registry(weightTool(), new AgentTestSupport.ProfileTool()), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "一直问下去",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(gateway.requests()).hasSize(LIMITS.maxModelCalls());
            assertThat(result.stopReason()).isEqualTo(StopReason.LOOP_EXHAUSTED);
            assertThat(result.runStatus()).isEqualTo("FAILED");
            assertThat(result.hasVerifiedFacts()).isTrue();
            assertThat(result.answerText()).contains("本轮没有生成自由文本结论");
        }

        @Test
        @DisplayName("最后一次允许的调用带 tool_choice=none 强制收束")
        void finalCallForcesToolChoiceNone() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(call("r1", "get_weight_trend", "{\"days\":7}")));
            client.enqueue(tools(call("r2", "get_profile", "{}")));
            client.enqueue(tools(call("r3", "get_weight_trend", "{\"days\":30}")));
            client.enqueue(answer("收尾结论"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

            AgentAnswer result = AgentTestSupport.runner(gateway,
                    AgentTestSupport.registry(weightTool(), new AgentTestSupport.ProfileTool()), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "综合看看",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(gateway.requests()).hasSize(4);
            assertThat(gateway.requests().get(0).toolChoice()).isEqualTo(ToolChoice.AUTO);
            assertThat(gateway.requests().get(2).toolChoice()).isEqualTo(ToolChoice.AUTO);
            assertThat(gateway.requests().get(3).toolChoice()).isEqualTo(ToolChoice.NONE);
            assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        }

        @Test
        @DisplayName("工具总次数上限触发 → LIMIT_STOPPED（我们的闸门，不是供应商限流）")
        void toolCallCapStopsRun() {
            // 总上限 1、单轮上限也 1（RunLimits 禁止单轮 > 总，否则轮内闸门是死代码）。
            // 一轮声明两个工具：t1 执行后总次数闸门先命中，这一轮不再往下走。
            RunLimits limits = new RunLimits(4, 1, 1, 8192, 1024, 120);
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(
                    call("t1", "get_weight_trend", "{\"days\":7}"),
                    call("t2", "get_profile", "{}")));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

            AgentAnswer result = AgentTestSupport.runner(gateway,
                    AgentTestSupport.registry(weightTool(), new AgentTestSupport.ProfileTool()), limits)
                    .run(AgentTestSupport.request(AgentTestSupport.context(limits), "两个都要",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(result.stopReason()).isEqualTo(StopReason.LIMIT_STOPPED);
            assertThat(gateway.requests()).hasSize(1);
        }

        @Test
        @DisplayName("单轮工具数上限触发 → LIMIT_STOPPED")
        void perRoundToolCapStopsRun() {
            RunLimits limits = new RunLimits(4, 8, 1, 8192, 1024, 120);
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(tools(
                    call("r-1", "get_weight_trend", "{\"days\":7}"),
                    call("r-2", "get_profile", "{}")));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

            AgentAnswer result = AgentTestSupport.runner(gateway,
                    AgentTestSupport.registry(weightTool(), new AgentTestSupport.ProfileTool()), limits)
                    .run(AgentTestSupport.request(AgentTestSupport.context(limits), "两个都要",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(result.stopReason()).isEqualTo(StopReason.LIMIT_STOPPED);
            assertThat(gateway.requests()).hasSize(1);
        }

        @Test
        @DisplayName("截止时间已过 → 一次调用都不发起")
        void deadlineStopsBeforeAnyCall() {
            RunLimits limits = RunLimits.defaults();
            AgentContext base = AgentTestSupport.context(limits);
            AgentContext expired = new AgentContext(base.runId(), base.runUuid(), base.userId(),
                    base.conversationId(), base.zone(), base.businessDate(), base.dataRevision(), base.credential(),
                    base.model(), base.budget(), Instant.parse("2026-10-13T01:59:00Z"), base.allowedTools());
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(answer("不该被调用"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), limits)
                    .run(AgentTestSupport.request(expired, "还在吗", TraceRecorder.inMemory(),
                            new EvidenceRegistry(), new RunCancellation(), List.of()));

            assertThat(gateway.requests()).isEmpty();
            assertThat(result.stopReason()).isEqualTo(StopReason.DEADLINE_STOPPED);
        }

        @Test
        @DisplayName("取消在第一次调用之前 → CANCELLED，不发出请求")
        void cancellationBeforeFirstCallSkipsRemote() {
            FakeModelClient client = AgentTestSupport.fake();
            client.enqueue(answer("不该被调用"));
            AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
            RunCancellation cancellation = new RunCancellation();
            cancellation.request();

            AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS)
                    .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "算了",
                            TraceRecorder.inMemory(), new EvidenceRegistry(), cancellation, List.of()));

            assertThat(gateway.requests()).isEmpty();
            assertThat(result.stopReason()).isEqualTo(StopReason.CANCELLED);
        }
    }

    // ---- 10. 预算耗尽 ----

    @Test
    @DisplayName("预算耗尽：不再发额外总结请求，用已验证事实 + BUDGET_STOPPED 返回")
    void budgetExhaustedReturnsVerifiedFactsWithoutExtraRequest() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(tools(call("b-1", "get_weight_trend", "{\"days\":7}")));
        client.enqueue(tools(call("b-2", "get_profile", "{}")));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client)
                .stopBeforeCall(2, com.hfj.fitagent.agent.core.ModelCallStatus.BUDGET_STOPPED,
                        "BUDGET_EXCEEDED_DAILY");
        AgentContext ctx = AgentTestSupport.context(LIMITS);

        AgentAnswer result = AgentTestSupport.runner(gateway,
                AgentTestSupport.registry(weightTool(), new AgentTestSupport.ProfileTool()), LIMITS)
                .run(AgentTestSupport.request(ctx, "看看我的体重和档案", TraceRecorder.inMemory(),
                        new EvidenceRegistry(), new RunCancellation(), List.of()));

        // 第二轮的工具已经执行完，但被拒绝的是第 2 次调用之后的下一次请求 → 只发出 2 次
        assertThat(gateway.requests()).hasSize(2);
        assertThat(result.stopReason()).isEqualTo(StopReason.BUDGET_STOPPED);
        assertThat(result.runStatus()).isEqualTo("BUDGET_STOPPED");
        assertThat(result.hasVerifiedFacts()).isTrue();
        assertThat(result.answerText()).contains("预算").doesNotContain("71.5 kg 的估算");
        assertThat(result.missingFields()).contains("ANSWER_NOT_GENERATED:BUDGET_STOPPED");
    }

    @Test
    @DisplayName("凭据缺失：一次远程调用都不发起，状态转 WAITING_CREDENTIAL")
    void missingCredentialStopsImmediately() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(answer("不该发生"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS)
                .run(AgentTestSupport.request(AgentTestSupport.contextNoCredential(LIMITS), "我的体重",
                        TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

        assertThat(gateway.requests()).isEmpty();
        assertThat(result.stopReason()).isEqualTo(StopReason.NO_CREDENTIAL);
        assertThat(result.runStatus()).isEqualTo("WAITING_CREDENTIAL");
    }

    @Test
    @DisplayName("供应商 401：run 以 PROVIDER_ERROR 结束并带上 CREDENTIAL_INVALID 错误码")
    void credentialInvalidSurfacesAsProviderError() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueueFailure(new com.hfj.fitagent.agent.model.ModelProviderException(
                com.hfj.fitagent.agent.model.ProviderErrorKind.CREDENTIAL_INVALID,
                "CREDENTIAL_INVALID", "供应商拒绝了凭据", true, 401));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS)
                .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "我的体重",
                        TraceRecorder.inMemory(), new EvidenceRegistry(), new RunCancellation(), List.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.PROVIDER_ERROR);
        assertThat(result.costUncertain()).isTrue();
        assertThat(result.costKnown()).isFalse();
    }

    // ---- data_revision 陈旧判定 ----

    @Test
    @DisplayName("run 结束时修订号变了 → isStale=true，事实保留原快照，不自动重跑")
    void staleDataRevisionIsFlagged() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(answer("直接回答"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        EvidenceRegistry evidence = new EvidenceRegistry();

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS,
                context -> context.dataRevision() + 1)
                .run(AgentTestSupport.request(AgentTestSupport.context(LIMITS), "现在怎么样",
                        TraceRecorder.inMemory(), evidence, new RunCancellation(), List.of()));

        assertThat(result.isStale()).isTrue();
        assertThat(result.dataRevision()).isEqualTo(AgentTestSupport.DATA_REVISION);
    }

    @Test
    @DisplayName("白名单外即使注册表里有工具也不发声明（工具面由服务端决定）")
    void toolsOutsideWhitelistAreNotDeclared() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(tools(call("blocked", "get_profile", "{}")));
        client.enqueue(answer("不行"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        AgentTestSupport.ProfileTool profileTool = new AgentTestSupport.ProfileTool();
        AgentContext ctx = AgentTestSupport.contextWithTools(Set.of("get_weight_trend"), LIMITS);

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(profileTool, weightTool()),
                LIMITS).run(AgentTestSupport.request(ctx, "看档案", TraceRecorder.inMemory(),
                        new EvidenceRegistry(), new RunCancellation(), List.of()));

        assertThat(gateway.requests().get(0).tools()).extracting(definition -> definition.name())
                .containsExactly("get_weight_trend");
        assertThat(profileTool.calls()).isZero();
        String blocked = gateway.requests().get(1).messages().stream()
                .filter(message -> "blocked".equals(message.toolCallId()))
                .findFirst().orElseThrow().content();
        assertThat(blocked).contains(ToolErrorCode.TOOL_NOT_ALLOWED);
        assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
    }

    @Test
    @DisplayName("轨迹是追加式的，且每一步都留下事件；模型调用失败不会因轨迹写入异常被重发")
    void traceIsAppendOnlyAndNeverDrivesRetries() {
        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(tools(call("tt-1", "get_weight_trend", "{\"days\":7}")));
        client.enqueue(answer("好了"));
        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        // 一个总是抛异常的 sink：模拟 agent_step 写入失败
        TraceRecorder trace = TraceRecorder.forRun(event -> {
            throw new IllegalStateException("落库失败");
        }, AgentTestSupport.clock());
        AgentContext ctx = AgentTestSupport.context(LIMITS);

        AgentAnswer result = AgentTestSupport.runner(gateway, AgentTestSupport.registry(weightTool()), LIMITS)
                .run(AgentTestSupport.request(ctx, "我的体重", trace, new EvidenceRegistry(),
                        new RunCancellation(), List.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        assertThat(gateway.requests()).hasSize(2);
        assertThat(trace.events()).isNotEmpty();
        assertThat(trace.events()).extracting(com.hfj.fitagent.agent.trace.AgentEvent::stepNo)
                .isSorted().doesNotHaveDuplicates();
    }
}
