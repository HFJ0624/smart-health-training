package com.hfj.fitagent.modules.coach.service;

import com.hfj.fitagent.agent.answer.AgentAnswer;
import com.hfj.fitagent.agent.answer.FactCard;
import com.hfj.fitagent.agent.answer.UsageSummary;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.AgentRunner;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.core.RunRequest;
import com.hfj.fitagent.agent.core.StopReason;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.coach.CoachProperties;
import com.hfj.fitagent.modules.coach.entity.AgentRunEntity;
import com.hfj.fitagent.modules.coach.entity.ChatConversationEntity;
import com.hfj.fitagent.modules.coach.entity.ChatMessageEntity;
import com.hfj.fitagent.modules.coach.mapper.AgentRunMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatConversationMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatMessageMapper;
import com.hfj.fitagent.modules.coach.mapper.CoachAccountingReadMapper;
import com.hfj.fitagent.modules.coach.mapper.ModelCallMapper;
import com.hfj.fitagent.modules.coach.support.AgentStepWriter;
import com.hfj.fitagent.modules.coach.support.CoachDataRevisionReader;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.entity.BudgetPolicyEntity;
import com.hfj.fitagent.modules.modelaccess.service.BudgetPolicyService;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * run 执行与结果持久化（docs/agent-v1-design.md §9、§10；提示词 B11、B12、B14 第 7、9 项）。
 *
 * <p>核心红线是：<b>完整结果必须落在 run 上</b>。页面重进、历史回放、费用后续更新都读同一份
 * {@code result_payload}；{@code agent_step} 的截断摘要只是排查材料，永远不能冒充结果。
 * 第二条：装配阶段就失败的 run 不写 assistant 消息——把内部错误写进会话历史，下一轮模型会把它
 * 当成"上一个回答"读进去。
 */
class RunExecutionServiceTest {

    private static final long USER_ID = 42L;
    private static final long RUN_ID = 88L;
    private static final long CONVERSATION_ID = 77L;
    private static final long PROVIDER_ID = 1L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-13T02:00:00Z");
    private static final String WORKER_ID = "worker-test";

    private AgentRunner runner;
    private AgentRunMapper runMapper;
    private ChatMessageMapper messageMapper;
    private CoachMessageWriter messages;
    private BudgetPolicyService budgetPolicyService;
    private ProviderCatalogService catalogService;
    private RunExecutionService service;

    @BeforeEach
    void setUp() {
        runner = mock(AgentRunner.class);
        runMapper = mock(AgentRunMapper.class);
        ChatConversationMapper conversationMapper = mock(ChatConversationMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        ModelCallMapper modelCallMapper = mock(ModelCallMapper.class);
        CoachAccountingReadMapper accountingMapper = mock(CoachAccountingReadMapper.class);
        catalogService = mock(ProviderCatalogService.class);
        budgetPolicyService = mock(BudgetPolicyService.class);
        CoachDataRevisionReader revisionReader = mock(CoachDataRevisionReader.class);
        AgentStepWriter stepWriter = mock(AgentStepWriter.class);
        messages = mock(CoachMessageWriter.class);

        when(runMapper.selectById(RUN_ID)).thenReturn(queuedRun());
        when(runMapper.claim(eq(RUN_ID), eq(WORKER_ID), any(Instant.class), any(Instant.class))).thenReturn(1);
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(List.of(userMessage()));
        when(modelCallMapper.sumSettledCost(USER_ID, RUN_ID)).thenReturn(new BigDecimal("0.0002100000"));
        when(accountingMapper.sumUncertainCommitted(USER_ID, RUN_ID)).thenReturn(BigDecimal.ZERO);
        when(runMapper.finishCas(anyLong(), anyLong(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(Instant.class))).thenReturn(1);
        when(catalogService.requireProvider("DEEPSEEK")).thenReturn(new ProviderRef(PROVIDER_ID, "DEEPSEEK",
                "DeepSeek", "https://api.deepseek.com", "DEEPSEEK_CHAT", true));
        when(catalogService.loadSelection(any(), eq(3L))).thenReturn(modelSelection());
        when(budgetPolicyService.requirePolicy(USER_ID, PROVIDER_ID, "CNY")).thenReturn(policy());
        when(revisionReader.zoneOf(USER_ID)).thenReturn(ZONE);
        when(stepWriter.forRun(USER_ID, RUN_ID)).thenReturn(event -> {
            // 轨迹在本用例里不落库：它是不是可丢的诊断材料，由 TraceRecorderTest 单独证明
        });

        service = new RunExecutionService(runner, runMapper, conversationMapper, messageMapper, modelCallMapper,
                accountingMapper, catalogService, budgetPolicyService, revisionReader,
                new ToolRegistry(List.of(), com.hfj.fitagent.agent.AgentTestSupport.mapper()),
                stepWriter, messages, new RunCancellationRegistry(), RunLimits.defaults(),
                CoachProperties.defaults(), com.hfj.fitagent.agent.AgentTestSupport.mapper(),
                new AppClock(Clock.fixed(NOW, ZoneId.of("UTC"))));
    }

    @Test
    @DisplayName("没抢到领取权就什么都不做：同一个 run 不由两个 Worker 并行执行")
    void lostClaimDoesNothingAtAll() {
        when(runMapper.claim(eq(RUN_ID), eq(WORKER_ID), any(Instant.class), any(Instant.class))).thenReturn(0);

        assertThat(service.execute(RUN_ID, WORKER_ID)).isFalse();

        verify(runner, never()).run(any());
        verify(runMapper, never()).finishCas(anyLong(), anyLong(), anyString(), anyString(), any(), any(),
                any(), any(), any());
        verify(messages, never()).append(anyLong(), anyLong(), any(), anyString(), anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("完整 AgentAnswer 落在 run 的 result_payload：事实卡片与来源不因截断摘要而丢失")
    void fullAnswerIsPersistedOnTheRun() {
        when(runner.run(any(RunRequest.class))).thenAnswer(invocation -> {
            RunRequest request = invocation.getArgument(0);
            assertThat(request.context().userId()).isEqualTo(USER_ID);
            assertThat(request.context().credential().credentialVersion()).isEqualTo(2);
            // 白名单本身由 ToolRegistry 的契约保证（这里注册表为空就是空），本用例只核对可信身份
            assertThat(request.context().runId()).isEqualTo(RUN_ID);
            assertThat(request.context().dataRevision()).isEqualTo(17L);
            return answer(StopReason.SUCCEEDED);
        });

        assertThat(service.execute(RUN_ID, WORKER_ID)).isTrue();

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> stopReason = ArgumentCaptor.forClass(String.class);
        verify(runMapper).finishCas(eq(RUN_ID), eq(USER_ID), status.capture(), stopReason.capture(),
                eq(null), payload.capture(), eq(new BigDecimal("0.0002100000")),
                eq(BigDecimal.ZERO), eq(NOW));
        assertThat(status.getValue()).isEqualTo("SUCCEEDED");
        // 库里存枚举名，wire 上才是 COMPLETED（§11.1 第 8 条）
        assertThat(stopReason.getValue()).isEqualTo("SUCCEEDED");

        JsonNode node = parse(payload.getValue());
        assertThat(node.path("factCards").get(0).path("evidenceId").asString())
                .isEqualTo("body_measurement:1001");
        assertThat(node.path("factCards").get(0).path("value").asString()).isEqualTo("72.4");
        assertThat(node.path("evidenceRefs").get(0).asString()).isEqualTo("body_measurement:1001");
        assertThat(node.path("stopReason").asString()).isEqualTo("COMPLETED");
        assertThat(node.has("costKnown")).as("§11.1 第 1 条：费用字段只在 run 响应上").isFalse();
        assertThat(node.has("costUncertain")).isFalse();
        assertThat(node.path("usage").path("modelCalls").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("助手消息按同一份结果落库：停止收尾写 INCOMPLETE，页面据此显示这不是完整回答")
    void assistantMessageCarriesFactsAndCompleteness() {
        when(runner.run(any(RunRequest.class))).thenReturn(answer(StopReason.LIMIT_STOPPED));

        service.execute(RUN_ID, WORKER_ID);

        ArgumentCaptor<List> cards = ArgumentCaptor.forClass(List.class);
        verify(messages).append(eq(USER_ID), eq(CONVERSATION_ID), eq(RUN_ID), eq("assistant"),
                eq("最近体重 72.4kg"), cards.capture(), eq(List.of("body_measurement:1001")),
                eq(List.of("今天没有训练记录")), eq(17L), eq(false), eq(false));
        // chat_message.fact_cards 存的是服务端取值的完整卡片（不是模型自填的数字）
        assertThat(cards.getValue()).hasSize(1);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> storedCard = (java.util.Map<String, Object>) cards.getValue().get(0);
        assertThat(storedCard).containsEntry("evidenceId", "body_measurement:1001")
                .containsEntry("value", "72.4")
                .containsEntry("sourceTable", "body_measurement");
        verify(runMapper).finishCas(eq(RUN_ID), eq(USER_ID), eq("LIMIT_STOPPED"), eq("LIMIT_STOPPED"),
                eq(null), anyString(), any(), any(), any(Instant.class));
    }

    @Test
    @DisplayName("终态已被取消写好时，迟到的结果不覆盖、也不再写助手消息")
    void lateResultDoesNotOverwriteATerminalRun() {
        when(runner.run(any(RunRequest.class))).thenReturn(answer(StopReason.SUCCEEDED));
        when(runMapper.finishCas(anyLong(), anyLong(), anyString(), anyString(), any(), anyString(),
                any(), any(), any(Instant.class))).thenReturn(0);

        service.execute(RUN_ID, WORKER_ID);

        verify(messages, never()).append(anyLong(), anyLong(), any(), anyString(), anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("上下文装配失败：run 转 FAILED 并带错误码，但不写 assistant 消息污染历史")
    void assemblyFailureFailsTheRunWithoutWritingAnAnswer() {
        when(budgetPolicyService.requirePolicy(USER_ID, PROVIDER_ID, "CNY"))
                .thenThrow(new com.hfj.fitagent.common.error.BizException(ErrorCode.MODEL_NOT_CONFIGURED,
                        "没有生效报价"));

        assertThat(service.execute(RUN_ID, WORKER_ID)).isTrue();

        ArgumentCaptor<String> errorCode = ArgumentCaptor.forClass(String.class);
        verify(runMapper).finishCas(eq(RUN_ID), eq(USER_ID), eq("FAILED"), eq("PROVIDER_ERROR"),
                errorCode.capture(), anyString(), any(), any(), any(Instant.class));
        assertThat(errorCode.getValue()).isEqualTo(ErrorCode.MODEL_NOT_CONFIGURED.name());
        verify(runner, never()).run(any());
        verify(messages, never()).append(anyLong(), anyLong(), any(), anyString(), anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("run 行不存在或已不是 QUEUED：直接退出，不覆盖任何已完成的状态")
    void finishedRunIsNotReexecuted() {
        AgentRunEntity done = queuedRun();
        done.setStatus("SUCCEEDED");
        when(runMapper.selectById(RUN_ID)).thenReturn(done);

        assertThat(service.execute(RUN_ID, WORKER_ID)).isFalse();
        verify(runMapper, never()).claim(anyLong(), anyString(), any(), any());
    }

    // ---- 夹具 ----

    private static JsonNode parse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (RuntimeException e) {
            throw new AssertionError("result_payload 不是合法 JSON: " + json, e);
        }
    }

    private static AgentRunEntity queuedRun() {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(RUN_ID);
        run.setRunUuid("3f2b1c4a-5d6e-4f70-8a9b-0c1d2e3f4a5b");
        run.setUserId(USER_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setCredentialId(5L);
        run.setCredentialVersion(2);
        run.setModelId(3L);
        run.setStatus("QUEUED");
        run.setCurrency("CNY");
        run.setContextRevision(17L);
        run.setLockVersion(0);
        return run;
    }

    private static ChatConversationEntity conversation() {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(CONVERSATION_ID);
        entity.setUserId(USER_ID);
        entity.setStatus("ACTIVE");
        return entity;
    }

    private static ChatMessageEntity userMessage() {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setId(400L);
        message.setUserId(USER_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setRunId(RUN_ID);
        message.setMessageNo(1);
        message.setRole("user");
        message.setContentText("我最近体重多少？");
        return message;
    }

    private static AgentAnswer answer(StopReason reason) {
        FactCard card = new FactCard("body_measurement:1001", "最近体重", "72.4", "kg",
                "body_measurement", "1001", LocalDate.of(2026, 10, 12), "profile-v1", 17L);
        return AgentAnswer.of(List.of(card), List.of("body_measurement:1001"),
                List.of("今天没有训练记录"), 17L, reason,
                new UsageSummary(2, 1, 500L, 60L, 10L, true),
                true, false, false, false, "最近体重 72.4kg");
    }

    private static com.hfj.fitagent.modules.modelaccess.api.ModelSelection modelSelection() {
        return new com.hfj.fitagent.modules.modelaccess.api.ModelSelection(PROVIDER_ID, "DEEPSEEK",
                "DEEPSEEK_CHAT", 3L, "deepseek-flash", "DeepSeek Flash", false, true, true, 65536, 4096,
                LocalDate.of(2026, 10, 1));
    }

    private static BudgetPolicyEntity policy() {
        BudgetPolicyEntity entity = new BudgetPolicyEntity();
        entity.setId(9L);
        entity.setUserId(USER_ID);
        entity.setProviderId(PROVIDER_ID);
        entity.setCurrency("CNY");
        entity.setEnabled(true);
        return entity;
    }
}
