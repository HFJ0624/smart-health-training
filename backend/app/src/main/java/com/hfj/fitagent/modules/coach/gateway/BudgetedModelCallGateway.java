package com.hfj.fitagent.modules.coach.gateway;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.ModelCallCommand;
import com.hfj.fitagent.agent.core.ModelCallGateway;
import com.hfj.fitagent.agent.core.ModelCallOutcome;
import com.hfj.fitagent.agent.core.ModelCallStatus;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.model.ModelClient;
import com.hfj.fitagent.agent.model.ModelClientLocator;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.model.ProviderErrorKind;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.modules.billing.BudgetGate;
import com.hfj.fitagent.modules.billing.UsageSettlement;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.billing.api.PricedUsage;
import com.hfj.fitagent.modules.billing.api.ReservationOutcome;
import com.hfj.fitagent.modules.billing.api.SettlementResult;
import com.hfj.fitagent.modules.billing.api.TokenUsage;
import com.hfj.fitagent.modules.coach.entity.ModelCallEntity;
import com.hfj.fitagent.modules.coach.mapper.ModelCallMapper;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 生产环境的 {@link ModelCallGateway}：预占 → 台账 → HTTP → 结算（docs/agent-v1-design.md §6.3、§10）。
 *
 * <p>放在 coach 而不是 billing，是因为它要写 coach 拥有的 {@code model_call} 台账；
 * 反过来让 billing 依赖 coach 的 Mapper 会形成循环，billing 与 modelaccess 只暴露既有接口。
 *
 * <p>四条不可让步的行为：
 * <ol>
 *   <li>每一次远程调用（含收尾调用与有界参数修正）都先过 {@link BudgetGate#reserve}；拒绝时<b>不发出任何请求</b>。</li>
 *   <li>预占事务在 HTTP 之前提交，等待响应期间不持有数据库行锁。</li>
 *   <li>已发出但结果未知 → {@link UsageSettlement#markUnknown} 保留不确定预占，绝不归零、绝不重发。</li>
 *   <li>只有可证明未送达（{@code requestSent=false}）才允许 {@link UsageSettlement#release}。</li>
 * </ol>
 *
 * <p>结算单价固定用 PEAK 档（protocol §7：节假日日历没有机器可读来源，不能假定谷段），
 * 所以"实际结算 ≤ 预占"在档位层面成立。
 */
@Component
public class BudgetedModelCallGateway implements ModelCallGateway {

    private static final Logger log = LoggerFactory.getLogger(BudgetedModelCallGateway.class);

    /** 字符→token 的保守估算除数：只用于预占，不宣称等于供应商的分词结果。 */
    private static final int CHARS_PER_TOKEN = 3;
    /** 每个声明的工具调用在请求体里的固定开销估算（id + 函数名 + JSON 结构）。 */
    private static final int PER_TOOL_CALL_CHARS = 64;

    private static final String LEDGER_SENT = "SENT";
    private static final String LEDGER_SUCCEEDED = "SUCCEEDED";
    private static final String LEDGER_UNKNOWN = "UNKNOWN";
    private static final String LEDGER_RELEASED = "RELEASED";

    private final BudgetGate budgetGate;
    private final UsageSettlement usageSettlement;
    private final BudgetContextProvider contextProvider;
    private final ProviderCatalogService catalogService;
    private final ModelClientLocator clientLocator;
    private final ModelCallMapper modelCallMapper;
    private final AppClock clock;

    public BudgetedModelCallGateway(BudgetGate budgetGate,
                                    UsageSettlement usageSettlement,
                                    BudgetContextProvider contextProvider,
                                    ProviderCatalogService catalogService,
                                    ModelClientLocator clientLocator,
                                    ModelCallMapper modelCallMapper,
                                    AppClock clock) {
        this.budgetGate = budgetGate;
        this.usageSettlement = usageSettlement;
        this.contextProvider = contextProvider;
        this.catalogService = catalogService;
        this.clientLocator = clientLocator;
        this.modelCallMapper = modelCallMapper;
        this.clock = clock == null ? new AppClock() : clock;
    }

    @Override
    public ModelCallOutcome call(ModelCallCommand command) {
        AgentContext context = command.context();
        ModelRequest request = command.request();
        RunCancellation cancellation = command.cancellation();

        if (!context.budget().complete()) {
            // 没有绑定的预算引用就没有"能花多少"的依据，直接拒绝（B10 禁止默认放行的闸门）
            return ModelCallOutcome.stopped(ModelCallStatus.BUDGET_STOPPED,
                    ErrorCode.BUDGET_EXCEEDED_TASK.name(), context.budget().unsetDetail());
        }

        BudgetContext budget;
        try {
            ProviderRef provider = catalogService.requireProvider(context.model().providerId());
            budget = contextProvider.contextFor(context.userId(), provider.providerId());
        } catch (BizException e) {
            return ModelCallOutcome.stopped(ModelCallStatus.PROVIDER_ERROR, e.errorCode().name(), e.getMessage());
        }
        if (!sameCurrency(context.budget().currency(), budget.currency())) {
            return ModelCallOutcome.stopped(ModelCallStatus.BUDGET_STOPPED,
                    ErrorCode.BUDGET_EXCEEDED_TASK.name(),
                    "本 run 绑定的预算币种与当前生效策略不一致，未发起调用");
        }

        ReservationOutcome reservation;
        try {
            reservation = budgetGate.reserve(context.userId(), budget, context.runId(), command.attemptNo(),
                    estimateInputTokens(request), context.model().maxOutputTokens());
        } catch (BizException e) {
            log.info("预算闸门拒绝发起调用 runId={} attempt={} code={}",
                    context.runId(), command.attemptNo(), e.errorCode().name());
            return ModelCallOutcome.stopped(ModelCallStatus.BUDGET_STOPPED, e.errorCode().name(), e.getMessage());
        }

        ModelCallEntity ledger = writeLedger(context, reservation, command.attemptNo());
        try {
            ModelClient client = clientLocator.forProvider(context.model().providerId());
            ModelResponse response = client.complete(request, context, cancellation);
            return settleAndClose(ledger, reservation, budget, response);
        } catch (ModelProviderException e) {
            return closeUnsuccessful(ledger, reservation, e);
        } catch (RuntimeException e) {
            // 无法证明请求没发出去，一律按"已发送、结果未知"处理：宁可多占额度也不假装这一问免费
            log.warn("模型调用出现未预期异常，按结果未知处理 runId={} type={}",
                    context.runId(), e.getClass().getSimpleName());
            return closeUnsuccessful(ledger, reservation, new ModelProviderException(
                    ProviderErrorKind.TRANSPORT_UNKNOWN, ErrorCode.PROVIDER_UNAVAILABLE.name(),
                    "调用未完成，用量不明", true, null));
        }
    }

    // ---- 成功路径 ----

    private ModelCallOutcome settleAndClose(ModelCallEntity ledger,
                                            ReservationOutcome reservation,
                                            BudgetContext budget,
                                            ModelResponse response) {
        ModelUsage usage = response.usage();
        SettlementResult settlement = null;
        try {
            settlement = usageSettlement.settle(reservation.token(),
                    new PricedUsage(toTokenUsage(usage), budget.peakPrices()));
        } catch (RuntimeException e) {
            // 结算异常不能把"已经拿到的回答"变成失败：预占继续占用额度，界面按不确定费用显示
            log.warn("用量结算异常，预占保持不变 runId={} type={}",
                    ledger.getRunId(), e.getClass().getSimpleName());
        }
        ledger.setStatus(LEDGER_SUCCEEDED);
        ledger.setFinishedAt(clock.now());
        ledger.setProviderRequestId(response.providerRequestId());
        applyUsage(ledger, usage);
        if (settlement != null && settlement.applied()) {
            ledger.setCalculatedCost(settlement.actualCost());
            ledger.setCurrency(settlement.currency());
        }
        patchLedger(ledger);
        return ModelCallOutcome.succeeded(response);
    }

    // ---- 失败路径 ----

    private ModelCallOutcome closeUnsuccessful(ModelCallEntity ledger,
                                               ReservationOutcome reservation,
                                               ModelProviderException error) {
        ModelCallStatus status = statusFor(error.kind());
        String code = error.providerErrorCode() == null ? error.kind().name() : error.providerErrorCode();
        String detail = error.getMessage();
        ledger.setFinishedAt(clock.now());
        try {
            if (error.requestSent()) {
                SettlementResult unknown = usageSettlement.markUnknown(reservation.token());
                ledger.setStatus(LEDGER_UNKNOWN);
                log.info("调用已发出但用量不明，预占转不确定 runId={} status={}",
                        ledger.getRunId(), unknown.status());
            } else {
                usageSettlement.release(reservation.token());
                ledger.setStatus(LEDGER_RELEASED);
            }
        } catch (RuntimeException e) {
            // 台账收尾失败不改变"这次调用没成功"，但必须按最坏情况记账：费用可能已经发生
            log.error("预占收尾失败，按不确定费用处理 runId={} type={}",
                    ledger.getRunId(), e.getClass().getSimpleName());
            ledger.setStatus(LEDGER_UNKNOWN);
        }
        patchLedger(ledger);
        return ModelCallOutcome.failed(status, code, detail, error.requestSent());
    }

    private static ModelCallStatus statusFor(ProviderErrorKind kind) {
        return switch (kind) {
            case CREDENTIAL_MISSING -> ModelCallStatus.NO_CREDENTIAL;
            case CREDENTIAL_INVALID -> ModelCallStatus.CREDENTIAL_INVALID;
            case INSUFFICIENT_BALANCE -> ModelCallStatus.INSUFFICIENT_BALANCE;
            case RATE_LIMITED -> ModelCallStatus.RATE_LIMITED;
            case PROVIDER_UNAVAILABLE -> ModelCallStatus.PROVIDER_UNAVAILABLE;
            case TRANSPORT_UNKNOWN, RESPONSE_MALFORMED -> ModelCallStatus.RESULT_UNKNOWN;
            // 配置层直接拒绝（例如把思考模式打开）：请求根本没构造出去，属于我方配置问题
            case INVALID_PARAMS, INVALID_REQUEST, PROTOCOL_MISMATCH, TRANSPORT_BEFORE_SEND,
                 CONFIGURATION_REJECTED -> ModelCallStatus.PROVIDER_ERROR;
        };
    }

    // ---- 台账 ----

    /**
     * 交棒给传输层之前先落一行 SENT + sent_at：进程在这之后崩溃，恢复规则才有
     * "可能已计费"的依据（§10）；确定没送达时同一行会被改写为 RELEASED。
     */
    private ModelCallEntity writeLedger(AgentContext context, ReservationOutcome reservation, int attemptNo) {
        ModelCallEntity entity = new ModelCallEntity();
        entity.setUserId(context.userId());
        entity.setRunId(context.runId());
        entity.setReservationId(reservation.token().reservationId());
        entity.setAttemptNo(attemptNo);
        entity.setModelId(context.model().modelId());
        entity.setCredentialVersion(context.credential() == null ? null : context.credential().credentialVersion());
        entity.setStatus(LEDGER_SENT);
        entity.setSentAt(clock.now());
        entity.setCurrency(reservation.currency());
        modelCallMapper.insert(entity);
        return entity;
    }

    /**
     * 回写台账。用整行回写而不是 wrapper：
     * {@code usage_payload} 是 JSON 列，只有实体字段上声明的 {@code Jackson3TypeHandler} 会参与写入，
     * wrapper 的 {@code set("usage_payload", map)} 绕过类型处理器会直接失败（AGENTS.md MyBatis-Plus 规则 2）。
     * 本行只有网关会写，内存里的 ledger 累积了全部已写过的列，所以整行回写不会抹掉任何东西。
     */
    private void patchLedger(ModelCallEntity ledger) {
        modelCallMapper.updateById(ledger);
    }

    private void applyUsage(ModelCallEntity ledger, ModelUsage usage) {
        if (usage == null) {
            return;
        }
        ledger.setPromptCacheHitTokens(usage.promptCacheHitTokens());
        ledger.setPromptCacheMissTokens(usage.promptCacheMissTokens());
        ledger.setCompletionTokens(usage.completionTokens());
        ledger.setReasoningTokens(usage.reasoningTokens());
        Map<String, Object> payload = new LinkedHashMap<>();
        // 缺字段就不写这个键：写 0 会把"供应商没给"伪装成"用量为 0"（protocol §6）
        put(payload, "prompt_cache_hit_tokens", usage.promptCacheHitTokens());
        put(payload, "prompt_cache_miss_tokens", usage.promptCacheMissTokens());
        put(payload, "prompt_tokens", usage.promptTokens());
        put(payload, "completion_tokens", usage.completionTokens());
        put(payload, "completion_tokens_details_reasoning_tokens", usage.reasoningTokens());
        ledger.setUsagePayload(payload.isEmpty() ? null : Map.copyOf(payload));
    }

    private static void put(Map<String, Object> target, String key, Long value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static TokenUsage toTokenUsage(ModelUsage usage) {
        if (usage == null) {
            return new TokenUsage(null, null, null, null);
        }
        return new TokenUsage(usage.promptCacheHitTokens(), usage.promptCacheMissTokens(),
                usage.completionTokens(), usage.reasoningTokens());
    }

    // ---- 估算 ----

    /**
     * 输入规模估算：消息正文与本轮工具 schema 的字符数，按 1 token / 3 字符向上取整。
     *
     * <p>刻意<b>不</b>假设缓存命中：命中与否由供应商决定，预占按全未命中计价才会算多不算少（§6.3）。
     * 这是我方闸门用的保守数字，不是供应商硬上限（B10）。
     */
    private static long estimateInputTokens(ModelRequest request) {
        long chars = 0;
        for (ModelMessage message : request.messages()) {
            chars += length(message.content());
            if (message.toolCalls() != null) {
                chars += message.toolCalls().size() * PER_TOOL_CALL_CHARS;
            }
        }
        for (ToolDefinition tool : request.tools()) {
            chars += length(tool.description()) + length(tool.jsonSchema());
        }
        return Math.max(1, (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }

    private static long length(String value) {
        return value == null ? 0 : value.length();
    }

    private static boolean sameCurrency(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().toUpperCase(Locale.ROOT).equals(right.trim().toUpperCase(Locale.ROOT));
    }
}
