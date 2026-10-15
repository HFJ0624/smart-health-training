package com.hfj.fitagent.modules.coach.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.fake.FakeModelProperties;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.common.AppClock;
import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;
import com.hfj.fitagent.common.web.FieldError;
import com.hfj.fitagent.common.util.Ids;
import com.hfj.fitagent.common.web.PageQuery;
import com.hfj.fitagent.common.web.PageResult;
import com.hfj.fitagent.modules.billing.CostCalculator;
import com.hfj.fitagent.modules.billing.api.BudgetContext;
import com.hfj.fitagent.modules.billing.api.BudgetContextProvider;
import com.hfj.fitagent.modules.coach.CoachProperties;
import com.hfj.fitagent.modules.coach.entity.AgentRunEntity;
import com.hfj.fitagent.modules.coach.entity.AgentStepEntity;
import com.hfj.fitagent.modules.coach.entity.ChatConversationEntity;
import com.hfj.fitagent.modules.coach.entity.ChatMessageEntity;
import com.hfj.fitagent.modules.coach.mapper.AgentRunMapper;
import com.hfj.fitagent.modules.coach.mapper.AgentStepMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatConversationMapper;
import com.hfj.fitagent.modules.coach.mapper.ChatMessageMapper;
import com.hfj.fitagent.modules.coach.mapper.CoachAccountingReadMapper;
import com.hfj.fitagent.modules.coach.support.CoachAmounts;
import com.hfj.fitagent.modules.coach.support.CoachCostEstimates;
import com.hfj.fitagent.modules.coach.support.CoachDataRevisionReader;
import com.hfj.fitagent.modules.coach.support.CoachStopReasons;
import com.hfj.fitagent.modules.coach.support.RequestHasher;
import com.hfj.fitagent.modules.coach.vo.CoachMessageVo;
import com.hfj.fitagent.modules.coach.vo.ConversationVo;
import com.hfj.fitagent.modules.coach.vo.EstimateVo;
import com.hfj.fitagent.modules.coach.vo.ItemsVo;
import com.hfj.fitagent.modules.coach.vo.RunStateVo;
import com.hfj.fitagent.modules.coach.vo.RunStepVo;
import com.hfj.fitagent.modules.coach.vo.RunSubmitVo;
import com.hfj.fitagent.modules.modelaccess.api.CredentialHandle;
import com.hfj.fitagent.modules.modelaccess.api.ModelSelection;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.service.ModelCredentialService;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import com.hfj.fitagent.modules.modelaccess.vo.ModelCredentialVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * AI 教练的会话与 run 编排（docs/agent-v1-design.md §8、§10、§11）。
 *
 * <p>三条贯穿全类的约定：
 * <ul>
 *   <li><b>身份只来自认证上下文</b>：{@code userId}/{@code zone} 由控制器从 {@code AuthenticatedUsers}
 *       取好再传进来，客户端传来的费用、用户 ID、模型能力、余额一律不参与判定；
 *       每条读写都带 {@code user_id} 条件，越权与不存在返回同一个 404 形状。</li>
 *   <li><b>活动 run 的闸门是数据库，不是页面</b>：会话里的第二个 QUEUED/RUNNING 撞
 *       {@code uq_agent_run_1(active_conversation_id)}，这里只做冲突翻译，不做"先查再插"的伪保护。</li>
 *   <li><b>预估不等于放行</b>：{@link #estimate} 与执行侧的保守预占同口径，
 *       但每一次真实调用仍单独过 {@code BudgetGate}（§11 约束）。</li>
 * </ul>
 */
@Service
public class CoachApplicationService {

    private static final Logger log = LoggerFactory.getLogger(CoachApplicationService.class);

    public static final int DEFAULT_MESSAGE_LIMIT = 50;
    public static final int MAX_MESSAGE_LIMIT = 200;
    public static final int STEP_LIMIT = 64;

    private static final String ROLE_USER = "user";
    private static final String RUN_QUEUED = "QUEUED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final CoachProperties properties;
    private final RunLimits limits;
    private final ToolRegistry toolRegistry;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AgentRunMapper runMapper;
    private final AgentStepMapper stepMapper;
    private final CoachAccountingReadMapper accountingMapper;
    private final ProviderCatalogService catalogService;
    private final ModelCredentialService credentialService;
    private final BudgetContextProvider contextProvider;
    private final CostCalculator costCalculator;
    private final CoachDataRevisionReader revisionReader;
    private final CoachMessageWriter messages;
    private final FakeModelProperties fakeModelProperties;
    private final RunDispatcher dispatcher;
    private final AppClock clock;

    public CoachApplicationService(CoachProperties properties,
                                   RunLimits limits,
                                   ToolRegistry toolRegistry,
                                   ChatConversationMapper conversationMapper,
                                   ChatMessageMapper messageMapper,
                                   AgentRunMapper runMapper,
                                   AgentStepMapper stepMapper,
                                   CoachAccountingReadMapper accountingMapper,
                                   ProviderCatalogService catalogService,
                                   ModelCredentialService credentialService,
                                   BudgetContextProvider contextProvider,
                                   CostCalculator costCalculator,
                                   CoachDataRevisionReader revisionReader,
                                   CoachMessageWriter messages,
                                   FakeModelProperties fakeModelProperties,
                                   RunDispatcher dispatcher,
                                   AppClock clock) {
        this.properties = properties;
        this.limits = limits;
        this.toolRegistry = toolRegistry;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.accountingMapper = accountingMapper;
        this.catalogService = catalogService;
        this.credentialService = credentialService;
        this.contextProvider = contextProvider;
        this.costCalculator = costCalculator;
        this.revisionReader = revisionReader;
        this.messages = messages;
        this.fakeModelProperties = fakeModelProperties;
        this.dispatcher = dispatcher;
        this.clock = clock == null ? new AppClock() : clock;
    }

    // ---- 会话 ----

    /** 新建会话：标题留空，由首个问题在服务端截取（§11.1 载荷契约：请求体是 {@code {}}）。 */
    @Transactional
    public ConversationVo createConversation(long userId) {
        ChatConversationEntity entity = new ChatConversationEntity();
        entity.setUserId(userId);
        entity.setStatus("ACTIVE");
        entity.setLockVersion(0);
        conversationMapper.insert(entity);
        return ConversationVo.from(entity);
    }

    @Transactional(readOnly = true)
    public PageResult<ConversationVo> listConversations(long userId, PageQuery query) {
        Page<ChatConversationEntity> page = new Page<>(query.page(), query.size(), true);
        Page<ChatConversationEntity> result = conversationMapper.selectPage(page,
                new QueryWrapper<ChatConversationEntity>()
                        .eq("user_id", userId)
                        .orderByDesc("last_message_at", "id"));
        return PageResult.from(result, result.getRecords().stream().map(ConversationVo::from).toList());
    }

    @Transactional(readOnly = true)
    public ConversationVo conversation(long userId, long conversationId) {
        return ConversationVo.from(requireConversation(userId, conversationId));
    }

    /** 归档是幂等的：已归档再调用返回当前状态而不是报错（§11.1 第 9 条）。 */
    @Transactional
    public ConversationVo archive(long userId, long conversationId) {
        requireConversation(userId, conversationId);
        conversationMapper.archive(conversationId, userId);
        return ConversationVo.from(requireConversation(userId, conversationId));
    }

    @Transactional(readOnly = true)
    public ItemsVo<CoachMessageVo> messages(long userId, long conversationId, Integer after, Integer limit) {
        requireConversation(userId, conversationId);
        int size = clamp(limit == null ? DEFAULT_MESSAGE_LIMIT : limit, 1, MAX_MESSAGE_LIMIT);
        QueryWrapper<ChatMessageEntity> wrapper = new QueryWrapper<ChatMessageEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", conversationId);
        if (after != null && after > 0) {
            wrapper.gt("message_no", after);
        }
        wrapper.orderByAsc("message_no").last("LIMIT " + size);
        return ItemsVo.of(messageMapper.selectList(wrapper).stream().map(CoachMessageVo::from).toList());
    }

    // ---- 预估（不发起任何收费请求）----

    @Transactional(readOnly = true)
    public EstimateVo estimate(long userId, long conversationId, String question, ZoneId zone) {
        requireConversation(userId, conversationId);
        String normalized = normalizeQuestion(question);
        Plan plan = planFor(userId, normalized, zone, false);
        BigDecimal dailyCommitted = accountingMapper.sumDailyCommitted(userId, plan.providerId(),
                plan.budget().currency(), plan.budgetDate());

        return new EstimateVo(
                CoachCostEstimates.assumptions(limits.maxModelCalls(), limits.maxToolCalls()),
                limits.maxModelCalls(),
                limits.maxToolCalls(),
                CoachAmounts.plain(plan.perCall()),
                CoachAmounts.plain(plan.total()),
                plan.budget().currency(),
                plan.budget().currency(),
                availableTools(),
                plan.credentialStatus(),
                CoachAmounts.plain(plan.budget().maxTaskCost()),
                CoachAmounts.plain(subtract(plan.budget().maxDailyCost(), dailyCommitted)),
                fakeModel());
    }

    // ---- 提交 ----

    /**
     * 幂等提交一个问题：落 run + user 消息，事务提交后派发后台执行。
     *
     * <p>同键同内容返回同一个 run（重放安全）；同键不同内容 → {@code IDEMPOTENCY_KEY_CONFLICT}——
     * 把另一个问题的结果当作本次的回答，比报错更糟。
     */
    @Transactional
    public RunSubmitVo submit(long userId, long conversationId, String question, String idempotencyKey, ZoneId zone) {
        ChatConversationEntity conversation = requireConversation(userId, conversationId);
        String normalized = normalizeQuestion(question);
        String key = requireIdempotencyKey(idempotencyKey);
        if (!"ACTIVE".equals(conversation.getStatus())) {
            throw new BizException(ErrorCode.INVALID_STATE_TRANSITION, "会话已归档，不能继续提问");
        }

        Instant now = clock.now();
        Plan plan = planFor(userId, normalized, zone, true);
        String requestHash = RequestHasher.hash(conversationId, normalized, null, plan.budgetDate());

        Long existing = runMapper.findIdByKey(userId, key);
        if (existing != null) {
            return replayOrConflict(userId, existing, requestHash);
        }

        AgentRunEntity run = new AgentRunEntity();
        run.setRunUuid(Ids.newUuid());
        run.setUserId(userId);
        run.setConversationId(conversationId);
        run.setIdempotencyKey(key);
        run.setRequestHash(requestHash);
        run.setCredentialId(plan.credentialId());
        run.setCredentialVersion(plan.credentialVersion());
        run.setModelId(plan.modelId());
        run.setStatus(RUN_QUEUED);
        run.setCurrency(plan.budget().currency());
        run.setContextRevision(plan.dataRevision());
        run.setEstimatedCost(plan.total());
        run.setLockVersion(0);
        run.setBudgetSnapshot(CoachCostEstimates.budgetSnapshot(plan.budget(), plan.providerId(),
                plan.modelId(), plan.estimatedInputTokens(), plan.dataRevision()));
        try {
            runMapper.insert(run);
        } catch (DuplicateKeyException e) {
            // 只翻译索引给出的结论：撞 uq_agent_run_1 是"会话里已有活动 run"，撞 uq_agent_run_2 是同键并发重放。
            // 这里不再"先查再插"，因为并发下查到的行随时会变，唯一索引才是唯一可信的闸门。
            Long raced = runMapper.findIdByKey(userId, key);
            if (raced != null) {
                log.info("幂等键并发重放 runId={} userId={}", raced, userId);
                return replayOrConflict(userId, raced, requestHash);
            }
            throw new BizException(ErrorCode.RUN_ALREADY_ACTIVE, "这个会话还有正在进行中的问题，请等待或先停止",
                    Map.of("conversationId", String.valueOf(conversationId)));
        }

        messages.append(userId, conversationId, run.getId(), ROLE_USER, normalized, null, null, null,
                plan.dataRevision(), false, true);
        messages.touch(userId, conversationId, titleOf(normalized), now);

        dispatcher.scheduleAfterCommit(run.getId());
        return new RunSubmitVo(String.valueOf(run.getId()), String.valueOf(conversationId), RUN_QUEUED,
                CoachAmounts.plain(plan.total()), plan.budget().currency(), key, fakeModel());
    }

    // ---- 状态 / 轨迹 / 取消 ----

    @Transactional(readOnly = true)
    public RunStateVo runState(long userId, long runId) {
        AgentRunEntity run = requireRun(userId, runId);
        RunStateVo.Progress progress = new RunStateVo.Progress(
                (int) stepMapper.countByEventType(userId, runId, "MODEL_CALL"),
                (int) stepMapper.countByEventType(userId, runId, "TOOL_RESULT"),
                (int) stepMapper.countByRun(userId, runId));
        return new RunStateVo(String.valueOf(run.getId()), String.valueOf(run.getConversationId()),
                run.getStatus(), CoachStopReasons.toWire(run.getStopReason()), progress,
                run.getResultPayload(),
                CoachAmounts.plain(run.getSettledCost()),
                CoachAmounts.plain(run.getUncertainCost()),
                run.getCurrency(),
                resultFlag(run.getResultPayload(), "isStale"),
                fakeModel(), run.getErrorCode(), run.getCreatedAt(), run.getFinishedAt());
    }

    @Transactional(readOnly = true)
    public ItemsVo<RunStepVo> runSteps(long userId, long runId) {
        requireRun(userId, runId);
        List<RunStepVo> items = stepMapper.selectList(new QueryWrapper<AgentStepEntity>()
                        .eq("user_id", userId).eq("run_id", runId)
                        .orderByAsc("step_no").last("LIMIT " + STEP_LIMIT))
                .stream().map(RunStepVo::from).toList();
        return ItemsVo.of(items);
    }

    /**
     * 幂等取消：QUEUED 直接转终态（一次调用都没发起，转了不丢任何"可能已计费"的信息），
     * RUNNING 只打标记，由执行中的 Worker 收敛到 CANCELLED。终态且非取消 → {@code RUN_NOT_CANCELLABLE}。
     *
     * <p>客户端停止轮询<b>不是</b>取消（§10）；已发出的请求不因取消而免费，费用按 run 上的实际值显示。
     */
    @Transactional
    public RunStateVo cancel(long userId, long runId) {
        AgentRunEntity run = requireRun(userId, runId);
        if (isTerminal(run.getStatus())) {
            if (!STATUS_CANCELLED.equals(run.getStatus())) {
                throw new BizException(ErrorCode.RUN_NOT_CANCELLABLE, "本次运行已经结束，无法取消");
            }
            return runState(userId, runId);
        }
        Instant now = clock.now();
        if (runMapper.cancelQueued(runId, userId, now) == 0) {
            runMapper.markCancelRequested(runId, userId, now);
        }
        dispatcher.notifyCancel(runId);
        return runState(userId, runId);
    }

    // ---- 内部 ----

    private ChatConversationEntity requireConversation(long userId, long conversationId) {
        ChatConversationEntity entity = conversationMapper.selectOne(new QueryWrapper<ChatConversationEntity>()
                .eq("id", conversationId).eq("user_id", userId));
        if (entity == null) {
            throw BizException.notFound();
        }
        return entity;
    }

    private AgentRunEntity requireRun(long userId, long runId) {
        AgentRunEntity run = runMapper.selectOne(new QueryWrapper<AgentRunEntity>()
                .eq("id", runId).eq("user_id", userId));
        if (run == null) {
            // 越权与不存在同形：不回答"这一行存在但不是你的"
            throw BizException.notFound();
        }
        return run;
    }

    /**
     * 提交与预估共用的一次解析：供应商、模型、预算上下文、凭据与保守费用。
     *
     * @param requireCredential 预估<b>不</b>要求可用凭据：输入区要在用户还没配 Key 时也能显示预估与限额，
     *                          并把 {@code credentialStatus=MISSING} 如实报出去；提交才必须拒绝。
     */
    private Plan planFor(long userId, String normalizedQuestion, ZoneId zone, boolean requireCredential) {
        ProviderRef provider = catalogService.requireProvider(properties.providerCode());
        ModelSelection model = catalogService.selectModel(provider, null);
        BudgetContext budget = contextProvider.contextFor(userId, provider.providerId());
        CredentialHandle credential = requireCredential
                ? credentialService.requireUsable(userId, provider.providerCode())
                : null;
        long estimatedInput = CoachCostEstimates.estimateInputTokens(normalizedQuestion);
        int maxOutput = limits.effectiveMaxOutputTokens(model.maxOutputTokens());
        BigDecimal perCall = CoachCostEstimates.perCall(costCalculator, budget, estimatedInput, maxOutput);
        return new Plan(provider.providerId(), model.modelId(),
                credential == null ? null : credential.credentialId(),
                credential == null ? null : credential.credentialVersion(), budget, estimatedInput, maxOutput, perCall,
                CoachCostEstimates.total(perCall, limits.maxModelCalls()),
                revisionReader.revisionOf(userId), clock.businessDate(zone),
                credentialStatus(userId, provider.providerCode()));
    }

    private RunSubmitVo replayOrConflict(long userId, long runId, String requestHash) {
        AgentRunEntity replay = requireRun(userId, runId);
        if (!requestHash.equals(replay.getRequestHash())) {
            throw new BizException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, "同一个幂等键不能用于不同的问题");
        }
        if (RUN_QUEUED.equals(replay.getStatus())) {
            // 上一次提交可能正好卡在"事务已提交、派发没跟上"的窗口里（进程被杀）：补一次派发
            dispatcher.scheduleNow(replay.getId());
        }
        return new RunSubmitVo(String.valueOf(replay.getId()), String.valueOf(replay.getConversationId()),
                replay.getStatus(), CoachAmounts.plain(replay.getEstimatedCost()), replay.getCurrency(),
                replay.getIdempotencyKey(), fakeModel());
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "问题不能为空");
        }
        String trimmed = question.trim();
        if (trimmed.length() > properties.maxQuestionChars()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "问题长度超出允许范围（上限 " + properties.maxQuestionChars() + " 字符）");
        }
        return trimmed;
    }

    /**
     * 幂等键必须是标准 UUID：列是 {@code CHAR(36)} 且约定"小写规范化"（V19 注释）。
     * 在这里就规范化，而不是让一个 40 字符的键穿到数据库变成 1406 数据过长——
     * 那时用户看到的是一次内部错误，而不是一句"你的键格式不对"。
     */
    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 必填",
                    List.of(FieldError.of("idempotencyKey", "REQUIRED", "缺少 Idempotency-Key", null)));
        }
        try {
            return Ids.canonicalUuid(key);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "Idempotency-Key 必须是标准 UUID",
                    List.of(FieldError.of("idempotencyKey", "INVALID_UUID",
                            "Idempotency-Key 必须是标准 UUID", key)));
        }
    }

    private String titleOf(String question) {
        int max = properties.titleFromQuestionChars();
        return question.length() <= max ? question : question.substring(0, max);
    }

    /** 缺失与失效要分开显示：MISSING 是"还没配 Key"，INVALID/REVOKED 是"配过但现在不能用"。 */
    private String credentialStatus(long userId, String providerCode) {
        ModelCredentialVo masked = credentialService.readMasked(userId, providerCode);
        if (masked == null) {
            return "MISSING";
        }
        String status = masked.status() == null ? "" : masked.status().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "ACTIVE" -> "ACTIVE";
            case "REVOKED" -> "REVOKED";
            default -> "INVALID";
        };
    }

    private List<String> availableTools() {
        List<String> names = new ArrayList<>(toolRegistry.names());
        names.sort(String::compareTo);
        return List.copyOf(names);
    }

    private boolean isTerminal(String status) {
        return status != null && !RUN_QUEUED.equals(status) && !"RUNNING".equals(status);
    }

    private static Boolean resultFlag(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        return value instanceof Boolean flag ? flag : null;
    }

    private static BigDecimal subtract(BigDecimal limit, BigDecimal committed) {
        if (limit == null) {
            return null;
        }
        return limit.subtract(committed == null ? BigDecimal.ZERO : committed)
                .setScale(CostCalculator.MONEY_SCALE, java.math.RoundingMode.HALF_UP);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean fakeModel() {
        return fakeModelProperties != null && fakeModelProperties.enabled();
    }

    /** 一次提交/预估解析出来的全部可信输入；把它们收在一个 record 里，避免八个参数各传各的。 */
    private record Plan(long providerId,
                        long modelId,
                        Long credentialId,
                        Integer credentialVersion,
                        BudgetContext budget,
                        long estimatedInputTokens,
                        int maxOutputTokens,
                        BigDecimal perCall,
                        BigDecimal total,
                        long dataRevision,
                        java.time.LocalDate budgetDate,
                        String credentialStatus) {
    }
}
