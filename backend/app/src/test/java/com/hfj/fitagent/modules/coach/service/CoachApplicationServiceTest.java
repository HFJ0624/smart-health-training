package com.hfj.fitagent.modules.coach.service;

import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.fake.FakeModelProperties;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.modules.billing.CostCalculator;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.billing.api.UnitPrices;
import com.hfj.fitagent.modules.coach.CoachProperties;
import com.hfj.fitagent.modules.coach.entity.AgentRunEntity;
import com.hfj.fitagent.modules.coach.entity.ChatConversationEntity;
import com.hfj.fitagent.modules.coach.entity.ChatMessageEntity;
import com.hfj.fitagent.modules.coach.mapper.AgentRunMapper;
import com.hfj.fitagent.modules.coach.mapper.AgentStepMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatConversationMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatMessageMapper;
import com.hfj.fitagent.modules.coach.mapper.CoachAccountingReadMapper;
import com.hfj.fitagent.modules.coach.support.CoachDataRevisionReader;
import com.hfj.fitagent.modules.coach.vo.EstimateVo;
import com.hfj.fitagent.modules.coach.vo.RunStateVo;
import com.hfj.fitagent.modules.coach.vo.RunSubmitVo;
import com.hfj.fitagent.modules.modelaccess.api.CredentialHandle;
import com.hfj.fitagent.modules.modelaccess.api.ModelSelection;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.service.ModelCredentialService;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import com.hfj.fitagent.modules.modelaccess.vo.ModelCredentialVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话/run 编排的契约测试（docs/agent-v1-design.md §8、§10、§11.1；提示词 B14 第 5、6、15 项）。
 *
 * <p>重点不在"方法返回了对象"，而在四条红线：
 * <ol>
 *   <li>没有可用凭据时<b>不建 run</b>，更不会留下一次可能计费的调用；</li>
 *   <li>活动 run 的闸门是唯一索引：{@code DuplicateKeyException} 才被翻译成
 *       {@code RUN_ALREADY_ACTIVE}，而不是"先查一下再插"；</li>
 *   <li>同幂等键同内容返回同一 run，同键不同内容报冲突——绝不把另一个问题的结果当作本次回答；</li>
 *   <li>金额与 ID 在响应里都是字符串（DECIMAL(20,10) 不经 JS 浮点）。</li>
 * </ol>
 */
class CoachApplicationServiceTest {

    private static final long USER_ID = 42L;
    private static final long CONVERSATION_ID = 77L;
    private static final long PROVIDER_ID = 1L;
    private static final long MODEL_ID = 3L;
    private static final long RUN_ID = 88L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-10-13T02:00:00Z");
    private static final String QUESTION = "今天安排练什么？";
    private static final String KEY = "3f2b1c4a-5d6e-4f70-8a9b-0c1d2e3f4a5b";

    private ChatConversationMapper conversationMapper;
    private ChatMessageMapper messageMapper;
    private AgentRunMapper runMapper;
    private AgentStepMapper stepMapper;
    private CoachAccountingReadMapper accountingMapper;
    private ProviderCatalogService catalogService;
    private ModelCredentialService credentialService;
    private CoachDataRevisionReader revisionReader;
    private CoachMessageWriter messages;
    private RunDispatcher dispatcher;
    private CoachApplicationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ChatConversationMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        runMapper = mock(AgentRunMapper.class);
        stepMapper = mock(AgentStepMapper.class);
        accountingMapper = mock(CoachAccountingReadMapper.class);
        catalogService = mock(ProviderCatalogService.class);
        credentialService = mock(ModelCredentialService.class);
        revisionReader = mock(CoachDataRevisionReader.class);
        messages = mock(CoachMessageWriter.class);
        dispatcher = mock(RunDispatcher.class);

        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        // Mockito 对返回 Long 的方法默认给 0 而不是 null：不显式桩成 null，第一次提交会被当成幂等重放
        when(runMapper.findIdByKey(anyLong(), anyString())).thenReturn(null);
        when(catalogService.requireProvider("DEEPSEEK")).thenReturn(new ProviderRef(PROVIDER_ID, "DEEPSEEK",
                "DeepSeek", "https://api.deepseek.com", "DEEPSEEK_CHAT", true));
        when(catalogService.selectModel(any(), any())).thenReturn(model());
        BudgetContextProvider contextProvider = mock(BudgetContextProvider.class);
        when(contextProvider.contextFor(anyLong(), anyLong())).thenReturn(budget());
        when(credentialService.requireUsable(USER_ID, "DEEPSEEK"))
                .thenReturn(new CredentialHandle(USER_ID, 5L, 2, PROVIDER_ID));
        when(credentialService.readMasked(USER_ID, "DEEPSEEK")).thenReturn(null);
        when(revisionReader.revisionOf(USER_ID)).thenReturn(17L);
        when(runMapper.insert(any(AgentRunEntity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, AgentRunEntity.class).setId(RUN_ID);
            return 1;
        });

        this.service = new CoachApplicationService(CoachProperties.defaults(), RunLimits.defaults(),
                new ToolRegistry(List.of(), com.hfj.fitagent.agent.AgentTestSupport.mapper()),
                conversationMapper, messageMapper, runMapper, stepMapper, accountingMapper,
                catalogService, credentialService, contextProvider, new CostCalculator(), revisionReader,
                messages, FakeModelProperties.disabled(), dispatcher,
                new AppClock(Clock.fixed(NOW, ZoneId.of("UTC"))));
    }

    // ---- 提交 ----

    @Test
    @DisplayName("没有可用凭据就不建 run：CREDENTIAL_MISSING 在写库之前抛出")
    void submitWithoutCredentialCreatesNothing() {
        when(credentialService.requireUsable(USER_ID, "DEEPSEEK"))
                .thenThrow(new BizException(ErrorCode.CREDENTIAL_MISSING, "尚未配置模型 API Key"));

        assertThatThrownBy(() -> service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.CREDENTIAL_MISSING);

        verify(runMapper, never()).insert(any(AgentRunEntity.class));
        verify(messages, never()).append(anyLong(), anyLong(), any(), anyString(), anyString(), any(), any(),
                any(), any(), anyBoolean(), anyBoolean());
        verify(dispatcher, never()).scheduleAfterCommit(anyLong());
    }

    @Test
    @DisplayName("提交落的是 QUEUED run + 绑定当场的凭据版本/模型/币种/修订号，派发安排在事务之后")
    void submitPersistsQueuedRunWithBoundCredentialVersion() {
        RunSubmitVo vo = service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE);

        ArgumentCaptor<AgentRunEntity> captor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runMapper).insert(captor.capture());
        AgentRunEntity run = captor.getValue();
        assertThat(run.getStatus()).isEqualTo("QUEUED");
        assertThat(run.getCredentialId()).isEqualTo(5L);
        // run 绑定凭据版本：执行中途用户替换 Key 不会悄悄换成另一把（§6.1）
        assertThat(run.getCredentialVersion()).isEqualTo(2);
        assertThat(run.getModelId()).isEqualTo(MODEL_ID);
        assertThat(run.getCurrency()).isEqualTo("CNY");
        assertThat(run.getContextRevision()).isEqualTo(17L);
        assertThat(run.getUserId()).isEqualTo(USER_ID);
        assertThat(run.getConversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(run.getRunUuid()).isNotBlank();
        assertThat(run.getRequestHash()).hasSize(64);

        assertThat(vo.runId()).isEqualTo("88");
        assertThat(vo.status()).isEqualTo("QUEUED");
        assertThat(vo.currency()).isEqualTo("CNY");
        verify(messages).append(eq(USER_ID), eq(CONVERSATION_ID), eq(RUN_ID), eq("user"), eq(QUESTION),
                any(), any(), any(), eq(17L), eq(false), eq(true));
        verify(dispatcher).scheduleAfterCommit(RUN_ID);
    }

    @Test
    @DisplayName("金额与 ID 在响应里都是字符串：DECIMAL(20,10) 不经 JS 浮点")
    void amountsAreStringsAndNeverBareNumbers() {
        RunSubmitVo vo = service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE);

        assertThat(vo.estimatedCost()).isInstanceOf(String.class);
        assertThat(vo.estimatedCost()).contains(".");
        assertThat(vo.estimatedCost()).hasSizeGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("会话里已有活动 run：唯一索引冲突翻译成 RUN_ALREADY_ACTIVE，不静默排队第二个问题")
    void activeRunPerConversationComesFromTheUniqueIndex() {
        when(runMapper.insert(any(AgentRunEntity.class)))
                .thenThrow(new DuplicateKeyException("uq_agent_run_1"));

        assertThatThrownBy(() -> service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RUN_ALREADY_ACTIVE);

        verify(dispatcher, never()).scheduleAfterCommit(anyLong());
    }

    @Test
    @DisplayName("同幂等键同内容返回同一个 run；内容不同返回冲突")
    void idempotencyKeyReturnsSameRunOrConflicts() {
        RunSubmitVo first = service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE);
        String hash = capturedHash();

        AgentRunEntity existing = runRow(RUN_ID, hash);
        when(runMapper.findIdByKey(USER_ID, KEY)).thenReturn(RUN_ID);
        when(runMapper.selectOne(any())).thenReturn(existing);

        RunSubmitVo replay = service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE);
        assertThat(replay.runId()).isEqualTo(first.runId());
        // 已排队的重放补一次派发：上一次可能正好卡在"事务已提交、派发没跟上"的窗口
        verify(dispatcher).scheduleNow(RUN_ID);
        verify(runMapper, times(1)).insert(any(AgentRunEntity.class));
    }

    @Test
    @DisplayName("同键不同问题必须冲突：把另一个问题的结果当作本次回答比报错更糟")
    void sameKeyWithDifferentContentIsAConflict() {
        service.submit(USER_ID, CONVERSATION_ID, QUESTION, KEY, ZONE);
        String hash = capturedHash();

        when(runMapper.findIdByKey(USER_ID, KEY)).thenReturn(RUN_ID);
        when(runMapper.selectOne(any())).thenReturn(runRow(RUN_ID, "另一个问题的哈希"));

        assertThatThrownBy(() -> service.submit(USER_ID, CONVERSATION_ID, "换个问题", KEY, ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
    }

    @Test
    @DisplayName("幂等键缺失即拒绝：没有键的重复提交会把一次提问变成两次付费调用")
    void missingIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> service.submit(USER_ID, CONVERSATION_ID, QUESTION, "  ", ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(runMapper, never()).insert(any(AgentRunEntity.class));
    }

    @Test
    @DisplayName("幂等键必须是 UUID：列是 CHAR(36)，格式错的键应当在 400 里说清楚，而不是让数据库报 1406")
    void nonUuidIdempotencyKeyIsRejectedBeforeTheDatabase() {
        assertThatThrownBy(() -> service.submit(USER_ID, CONVERSATION_ID, QUESTION, "not-a-uuid", ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        verify(runMapper, never()).insert(any(AgentRunEntity.class));
    }

    @Test
    @DisplayName("超长问题不截断后照常计费，直接拒绝")
    void oversizedQuestionIsRejectedNotTruncated() {
        String longText = "练".repeat(CoachProperties.DEFAULT_MAX_QUESTION_CHARS + 1);

        assertThatThrownBy(() -> service.submit(USER_ID, CONVERSATION_ID, longText, KEY, ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("别人的会话与不存在的会话同形：一律 404，不泄露这一行存在但不是你的")
    void foreignConversationIsIndistinguishableFromMissing() {
        when(conversationMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.estimate(USER_ID, 999L, QUESTION, ZONE))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    // ---- 预估 ----

    @Test
    @DisplayName("预估不调用收费模型、不要求凭据，但把凭据状态如实报出来")
    void estimateNeedsNoCredentialAndReportsItsState() {
        EstimateVo vo = service.estimate(USER_ID, CONVERSATION_ID, QUESTION, ZONE);

        assertThat(vo.credentialStatus()).isEqualTo("MISSING");
        assertThat(vo.fakeModel()).isFalse();
        assertThat(vo.assumptions()).anyMatch(line -> line.contains("上限 4 次"));
        assertThat(vo.availableTools()).isEmpty();
        assertThat(vo.maxModelCalls()).isEqualTo(RunLimits.DEFAULT_MAX_MODEL_CALLS);
        assertThat(vo.estimatedCostLow()).isInstanceOf(String.class);
        assertThat(vo.estimatedCostHigh()).isInstanceOf(String.class);
        verify(credentialService, never()).requireUsable(anyLong(), anyString());
    }

    @Test
    @DisplayName("预估的两种凭据状态分开显示：配过但失效不等于还没配")
    void estimateDistinguishesMissingFromInvalidCredential() {
        when(credentialService.readMasked(USER_ID, "DEEPSEEK"))
                .thenReturn(new ModelCredentialVo("9", "1", "DEEPSEEK", null, "abcd", "INVALID",
                        1, "v1", null, null, NOW, NOW));

        assertThat(service.estimate(USER_ID, CONVERSATION_ID, QUESTION, ZONE).credentialStatus())
                .isEqualTo("INVALID");
    }

    // ---- 状态与取消 ----

    @Test
    @DisplayName("run 状态里成功映射为 COMPLETED，费用只在 run 响应上且是字符串")
    void runStateMapsStopReasonAndKeepsAmountsOnRun() {
        AgentRunEntity run = runRow(RUN_ID, "hash");
        run.setStatus("SUCCEEDED");
        run.setStopReason("SUCCEEDED");
        run.setSettledCost(new BigDecimal("0.0002100000"));
        run.setUncertainCost(BigDecimal.ZERO);
        when(runMapper.selectOne(any())).thenReturn(run);

        RunStateVo vo = service.runState(USER_ID, RUN_ID);

        assertThat(vo.stopReason()).isEqualTo("COMPLETED");
        assertThat(vo.costKnown()).isEqualTo("0.0002100000");
        assertThat(vo.costUncertain()).isEqualTo("0.0000000000");
        assertThat(vo.answer()).isNotNull();
        assertThat(vo.answer()).doesNotContainKeys("costKnown", "costUncertain");
    }

    @Test
    @DisplayName("未识别的 stop_reason 原样显示机器码，不猜一个认识的原因")
    void unknownStopReasonPassesThroughVerbatim() {
        AgentRunEntity run = runRow(RUN_ID, "hash");
        run.setStopReason("SOMETHING_NEW");
        when(runMapper.selectOne(any())).thenReturn(run);

        assertThat(service.runState(USER_ID, RUN_ID).stopReason()).isEqualTo("SOMETHING_NEW");
    }

    @Test
    @DisplayName("已经结束的 run 不能取消；已取消的重复取消是幂等的")
    void cancelIsIdempotentOnlyForCancelledRuns() {
        AgentRunEntity failed = runRow(RUN_ID, "hash");
        failed.setStatus("FAILED");
        failed.setStopReason("PROTOCOL_INCOMPLETE");
        when(runMapper.selectOne(any())).thenReturn(failed);

        assertThatThrownBy(() -> service.cancel(USER_ID, RUN_ID))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.RUN_NOT_CANCELLABLE);

        AgentRunEntity cancelled = runRow(RUN_ID, "hash");
        cancelled.setStatus("CANCELLED");
        cancelled.setStopReason("CANCELLED");
        when(runMapper.selectOne(any())).thenReturn(cancelled);
        assertThat(service.cancel(USER_ID, RUN_ID).status()).isEqualTo("CANCELLED");
        verify(runMapper, never()).cancelQueued(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("RUNNING 的取消只打标记并通知在途线程，不改终态（终态由 Worker 收敛）")
    void cancelWhileRunningOnlyMarksAndSignals() {
        AgentRunEntity running = runRow(RUN_ID, "hash");
        running.setStatus("RUNNING");
        when(runMapper.selectOne(any())).thenReturn(running);
        when(runMapper.cancelQueued(RUN_ID, USER_ID, NOW)).thenReturn(0);

        service.cancel(USER_ID, RUN_ID);

        verify(runMapper).markCancelRequested(RUN_ID, USER_ID, NOW);
        verify(dispatcher).notifyCancel(RUN_ID);
    }

    @Test
    @DisplayName("分页参数走统一口径：size 上限之外的值一律拒绝")
    void conversationPagingUsesTheSharedPageContract() {
        assertThatThrownBy(() -> service.listConversations(USER_ID,
                PageQuery.of(1, PageQuery.MAX_SIZE + 1, null, List.of("lastMessageAt"), "lastMessageAt")))
                .isInstanceOf(BizException.class);
    }

    // ---- 夹具 ----

    private String capturedHash() {
        ArgumentCaptor<AgentRunEntity> captor = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runMapper).insert(captor.capture());
        return captor.getValue().getRequestHash();
    }

    private static ChatConversationEntity conversation() {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setId(CONVERSATION_ID);
        entity.setUserId(USER_ID);
        entity.setStatus("ACTIVE");
        entity.setLockVersion(0);
        return entity;
    }

    private static AgentRunEntity runRow(long id, String requestHash) {
        AgentRunEntity run = new AgentRunEntity();
        run.setId(id);
        run.setUserId(USER_ID);
        run.setConversationId(CONVERSATION_ID);
        run.setRunUuid("3f2b1c4a-5d6e-4f70-8a9b-0c1d2e3f4a5b");
        run.setStatus("QUEUED");
        run.setRequestHash(requestHash);
        run.setIdempotencyKey(KEY);
        run.setCurrency("CNY");
        run.setContextRevision(17L);
        run.setResultPayload(java.util.Map.of("answerText", "今天练背", "isStale", false));
        return run;
    }

    private static ModelSelection model() {
        return new ModelSelection(PROVIDER_ID, "DEEPSEEK", "DEEPSEEK_CHAT", MODEL_ID, "deepseek-flash",
                "DeepSeek Flash", false, true, true, 65536, 4096, java.time.LocalDate.of(2026, 10, 1));
    }

    private static BudgetContext budget() {
        return new BudgetContext(PROVIDER_ID, "DEEPSEEK", "CNY",
                new UnitPrices("CNY", new BigDecimal("0.02"), new BigDecimal("1.00"),
                        new BigDecimal("2.00"), 3, "PEAK"),
                new BigDecimal("10.00"), new BigDecimal("1.00"), 8, true);
    }
}
