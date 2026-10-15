package com.hfj.fitagent.modules.coach.gateway;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.BudgetReference;
import com.hfj.fitagent.agent.core.ModelCallCommand;
import com.hfj.fitagent.agent.core.ModelCallOutcome;
import com.hfj.fitagent.agent.core.ModelCallStatus;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelClient;
import com.hfj.fitagent.agent.model.ModelClientLocator;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.billing.BudgetGate;
import com.hfj.fitagent.modules.billing.UsageSettlement;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.billing.api.PricedUsage;
import com.hfj.fitagent.modules.billing.api.ReservationOutcome;
import com.hfj.fitagent.modules.billing.api.ReservationToken;
import com.hfj.fitagent.modules.billing.api.SettlementResult;
import com.hfj.fitagent.modules.billing.api.TokenUsage;
import com.hfj.fitagent.modules.billing.api.UnitPrices;
import com.hfj.fitagent.modules.coach.entity.ModelCallEntity;
import com.hfj.fitagent.modules.coach.mapper.ModelCallMapper;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.StandardEnvironment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 费用关口的真实契约测试（docs/agent-v1-design.md §6.3、§10；提示词 B10 / B14 第 11–13 项）。
 *
 * <p>这一层是"钱到底怎么算"的唯一落点，所以断言全部打在**副作用顺序**上：
 * 先预占、后发请求、按调用幂等结算；已发出但结果未知时保留不确定预占且<b>不重发</b>；
 * 只有可证明未送达才释放。用假对象返回"看起来成功"的结果，是这些测试最不能接受的事。
 */
class BudgetedModelCallGatewayTest {

    private static final long PROVIDER_ID = 1L;
    private static final ReservationToken TOKEN = new ReservationToken(AgentTestSupport.USER_ID, 700L);

    private BudgetGate budgetGate;
    private UsageSettlement settlement;
    private BudgetContextProvider contextProvider;
    private ProviderCatalogService catalogService;
    private ModelCallMapper modelCallMapper;
    private StubClient client;
    private BudgetedModelCallGateway gateway;

    @BeforeEach
    void setUp() {
        budgetGate = mock(BudgetGate.class);
        settlement = mock(UsageSettlement.class);
        contextProvider = mock(BudgetContextProvider.class);
        catalogService = mock(ProviderCatalogService.class);
        modelCallMapper = mock(ModelCallMapper.class);
        client = new StubClient();
        ModelClientLocator locator = new ModelClientLocator(List.of(client), new StandardEnvironment());
        gateway = new BudgetedModelCallGateway(budgetGate, settlement, contextProvider, catalogService,
                locator, modelCallMapper, AgentTestSupport.clock());

        when(catalogService.requireProvider(AgentTestSupport.PROVIDER_ID))
                .thenReturn(new ProviderRef(PROVIDER_ID, AgentTestSupport.PROVIDER_ID, "DeepSeek",
                        "https://api.deepseek.com", "DEEPSEEK_CHAT", true));
        when(contextProvider.contextFor(anyLong(), anyLong())).thenReturn(budget());
        when(modelCallMapper.insert(any(ModelCallEntity.class))).thenAnswer(invocation -> {
            ModelCallEntity entity = invocation.getArgument(0);
            entity.setId(500L);
            return 1;
        });
        when(budgetGate.reserve(anyLong(), any(BudgetContext.class), anyLong(), anyInt(), anyLong(), anyInt()))
                .thenReturn(new ReservationOutcome(TOKEN, PROVIDER_ID, "CNY",
                        LocalDate.of(2026, 10, 13), 1, new BigDecimal("0.0004000000"), false));
        when(settlement.settle(any(), any())).thenAnswer(invocation -> new SettlementResult(TOKEN, true,
                "SETTLED", new BigDecimal("0.0002100000"), "CNY", false, false, null));
    }

    @Test
    @DisplayName("正常路径：先预占、再发请求、按官方 usage 结算，台账带 sent_at 与实际费用")
    void reservesBeforeSendingAndSettlesAfterwards() {
        client.response = response(100L, 400L, 50L);

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.SUCCEEDED);
        assertThat(outcome.succeeded()).isTrue();
        assertThat(client.calls.get()).isEqualTo(1);

        ArgumentCaptor<PricedUsage> priced = ArgumentCaptor.forClass(PricedUsage.class);
        verify(settlement).settle(eq(TOKEN), priced.capture());
        assertThat(priced.getValue().usage().hitTokens()).isEqualTo(100L);
        assertThat(priced.getValue().usage().missTokens()).isEqualTo(400L);
        assertThat(priced.getValue().usage().outputTokens()).isEqualTo(50L);
        // PEAK 档单价：结算不会因"当前正好是谷段"而算少（protocol §7）
        assertThat(priced.getValue().prices().rateTier()).isEqualTo("PEAK");

        ArgumentCaptor<ModelCallEntity> ledger = ArgumentCaptor.forClass(ModelCallEntity.class);
        verify(modelCallMapper).updateById(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(ledger.getValue().getSentAt()).isNotNull();
        assertThat(ledger.getValue().getFinishedAt()).isNotNull();
        assertThat(ledger.getValue().getCalculatedCost()).isEqualByComparingTo("0.0002100000");
        assertThat(ledger.getValue().getUsagePayload()).containsEntry("prompt_cache_hit_tokens", 100L);
        assertThat(ledger.getValue().getProviderRequestId()).isEqualTo("req-1");

        verify(settlement, never()).markUnknown(any());
        verify(settlement, never()).release(any());
    }

    @Test
    @DisplayName("预算闸门拒绝时一个字节都不发出：不发 HTTP、不落台账、不结算")
    void budgetRejectionMeansNoRequestAtAll() {
        when(budgetGate.reserve(anyLong(), any(BudgetContext.class), anyLong(), anyInt(), anyLong(), anyInt()))
                .thenThrow(new BizException(ErrorCode.BUDGET_EXCEEDED_DAILY, "当日额度已用尽"));

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.BUDGET_STOPPED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED_DAILY.name());
        assertThat(outcome.requestSent()).isFalse();
        assertThat(client.calls.get()).isZero();
        verify(modelCallMapper, never()).insert(any(ModelCallEntity.class));
        verify(settlement, never()).settle(any(), any());
    }

    @Test
    @DisplayName("run 没绑定预算引用就直接拒绝：禁止一个默认放行的闸门（B10）")
    void missingBudgetReferenceIsRefusedNotIgnored() {
        AgentContext base = AgentTestSupport.context(RunLimits.defaults());
        AgentContext unbound = withBudget(base, BudgetReference.unset());

        ModelCallOutcome outcome = gateway.call(new ModelCallCommand(unbound, request(), new RunCancellation(), 1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.BUDGET_STOPPED);
        assertThat(outcome.safeDetail()).contains("预算引用不完整");
        verify(budgetGate, never()).reserve(anyLong(), any(BudgetContext.class), anyLong(), anyInt(),
                anyLong(), anyInt());
        assertThat(client.calls.get()).isZero();
    }

    @Test
    @DisplayName("生效策略币种与 run 绑定币种不一致时不发起调用：跨币种相减等于凭空改额度")
    void currencyDriftStopsBeforeReserving() {
        when(contextProvider.contextFor(anyLong(), anyLong())).thenReturn(
                new BudgetContext(PROVIDER_ID, AgentTestSupport.PROVIDER_ID, "USD",
                        prices("USD"), new BigDecimal("10.00"), new BigDecimal("1.00"), 8, true));

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.BUDGET_STOPPED);
        assertThat(outcome.errorCode()).isEqualTo(ErrorCode.BUDGET_EXCEEDED_TASK.name());
        verify(budgetGate, never()).reserve(anyLong(), any(BudgetContext.class), anyLong(), anyInt(),
                anyLong(), anyInt());
    }

    @Test
    @DisplayName("已发出但结果未知：预占转 UNCERTAIN 继续占额度，绝不释放、绝不重发")
    void sentButUnknownKeepsReservationHeld() {
        client.failure = new ModelProviderException(ProviderErrorKind.TRANSPORT_UNKNOWN,
                "TIMEOUT", "读取超时", true, null);

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.RESULT_UNKNOWN);
        assertThat(outcome.requestSent()).isTrue();
        assertThat(client.calls.get()).isEqualTo(1);
        verify(settlement).markUnknown(TOKEN);
        verify(settlement, never()).release(any());
        verify(settlement, never()).settle(any(), any());
        ArgumentCaptor<ModelCallEntity> ledger = ArgumentCaptor.forClass(ModelCallEntity.class);
        verify(modelCallMapper).updateById(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo("UNKNOWN");
        assertThat(ledger.getValue().getCalculatedCost()).isNull();
    }

    @Test
    @DisplayName("可证明未送达（连接都没建立）才释放预占，台账写 RELEASED")
    void provablyNotSentReleasesReservation() {
        client.failure = new ModelProviderException(ProviderErrorKind.TRANSPORT_BEFORE_SEND,
                "TIMEOUT", "请求未发出", false, null);

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.PROVIDER_ERROR);
        assertThat(outcome.requestSent()).isFalse();
        verify(settlement).release(TOKEN);
        verify(settlement, never()).markUnknown(any());
        ArgumentCaptor<ModelCallEntity> ledger = ArgumentCaptor.forClass(ModelCallEntity.class);
        verify(modelCallMapper).updateById(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("未预期异常一律按\"可能已发出\"处理：宁可多占额度，也不假装这一问免费")
    void unexpectedRuntimeErrorIsTreatedAsUnknown() {
        client.failure = new IllegalStateException("装配之外的意外");

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.RESULT_UNKNOWN);
        assertThat(outcome.requestSent()).isTrue();
        verify(settlement).markUnknown(TOKEN);
        verify(settlement, never()).release(any());
    }

    @Test
    @DisplayName("401 之后供应商侧凭据失效映射成 CREDENTIAL_INVALID，由界面提示重新配置")
    void credentialInvalidIsMappedDistinctly() {
        client.failure = new ModelProviderException(ProviderErrorKind.CREDENTIAL_INVALID,
                "INVALID_API_KEY", "Key 无效", false, 401);

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.status()).isEqualTo(ModelCallStatus.CREDENTIAL_INVALID);
        assertThat(outcome.errorCode()).isEqualTo("INVALID_API_KEY");
        verify(settlement).release(TOKEN);
    }

    @Test
    @DisplayName("结算异常不吞掉已经拿到的回答：预占保持原状，费用按不确定处理")
    void settlementFailureDoesNotDiscardTheAnswer() {
        client.response = response(10L, 20L, 5L);
        doThrow(new BizException(ErrorCode.VERSION_CONFLICT, "预算账户与台账不一致"))
                .when(settlement).settle(any(), any());

        ModelCallOutcome outcome = gateway.call(command(1));

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.response().content()).isEqualTo("结论");
        ArgumentCaptor<ModelCallEntity> ledger = ArgumentCaptor.forClass(ModelCallEntity.class);
        verify(modelCallMapper).updateById(ledger.capture());
        assertThat(ledger.getValue().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(ledger.getValue().getCalculatedCost()).isNull();
    }

    @Test
    @DisplayName("供应商没给 usage 时不补 0：分项全部为 null 交给 CostCalculator 判未知")
    void missingUsageIsNotFilledWithZero() {
        client.response = new ModelResponse("结论", List.of(), FinishReason.STOP,
                ModelUsage.unknown(), "req-1", false);

        gateway.call(command(1));

        ArgumentCaptor<PricedUsage> priced = ArgumentCaptor.forClass(PricedUsage.class);
        verify(settlement).settle(eq(TOKEN), priced.capture());
        TokenUsage usage = priced.getValue().usage();
        assertThat(usage.hitTokens()).isNull();
        assertThat(usage.missTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        assertThat(usage.hasUnknownPricedField()).isTrue();
    }

    // ---- 夹具 ----

    private ModelCallCommand command(int attemptNo) {
        return new ModelCallCommand(AgentTestSupport.context(RunLimits.defaults()), request(),
                new RunCancellation(), attemptNo);
    }

    private static ModelRequest request() {
        return new ModelRequest(AgentTestSupport.MODEL_CODE,
                List.of(ModelMessage.system("规则"), ModelMessage.user("我最近体重多少？")),
                List.of(), null, com.hfj.fitagent.agent.model.ThinkingMode.DISABLED, 1024, null,
                java.time.Duration.ofSeconds(30));
    }

    private static ModelResponse response(long hit, long miss, long completion) {
        return new ModelResponse("结论", List.of(), FinishReason.STOP,
                ModelUsage.of(hit, miss, completion, 0L), "req-1", false);
    }

    private static BudgetContext budget() {
        return new BudgetContext(PROVIDER_ID, AgentTestSupport.PROVIDER_ID, "CNY",
                prices("CNY"), new BigDecimal("10.00"), new BigDecimal("1.00"), 8, true);
    }

    private static UnitPrices prices(String currency) {
        return new UnitPrices(currency, new BigDecimal("0.02"), new BigDecimal("1.00"),
                new BigDecimal("2.00"), 3, "PEAK");
    }

    private static AgentContext withBudget(AgentContext base, BudgetReference budget) {
        return new AgentContext(base.runId(), base.runUuid(), base.userId(), base.conversationId(), base.zone(),
                base.businessDate(), base.dataRevision(), base.credential(), base.model(), budget,
                base.deadline(), base.allowedTools());
    }

    /** 只记录被调用次数的假客户端：它不是供应商，仅用于验证网关的时序与台账。 */
    private static final class StubClient implements ModelClient {

        final AtomicInteger calls = new AtomicInteger();
        ModelResponse response;
        RuntimeException failure;

        @Override
        public String providerId() {
            return AgentTestSupport.PROVIDER_ID;
        }

        @Override
        public ModelResponse complete(ModelRequest request, AgentContext context) {
            return complete(request, context, new RunCancellation());
        }

        @Override
        public ModelResponse complete(ModelRequest request, AgentContext context, RunCancellation cancellation) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
