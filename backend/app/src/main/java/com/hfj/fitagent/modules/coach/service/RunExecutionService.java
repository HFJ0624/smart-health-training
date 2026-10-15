package com.hfj.fitagent.modules.coach.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hfj.fitagent.agent.answer.AgentAnswer;
import com.hfj.fitagent.agent.answer.FactCard;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.BudgetReference;
import com.hfj.fitagent.agent.core.CredentialHandle;
import com.hfj.fitagent.agent.core.ModelSelection;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.core.RunRequest;
import com.hfj.fitagent.agent.core.StopReason;
import com.hfj.fitagent.agent.model.ModelCapabilities;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import com.hfj.fitagent.agent.trace.TraceRecorder;
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
import com.hfj.fitagent.modules.coach.support.CoachDataRevisionReader;
import com.hfj.fitagent.modules.coach.support.CoachStopReasons;
import com.hfj.fitagent.modules.modelaccess.api.ProviderRef;
import com.hfj.fitagent.modules.modelaccess.entity.BudgetPolicyEntity;
import com.hfj.fitagent.modules.modelaccess.service.BudgetPolicyService;
import com.hfj.fitagent.modules.modelaccess.service.ProviderCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一个已领取的 run 跑完并把结果持久化（docs/agent-v1-design.md §9、§10）。
 *
 * <p>三件事只有这里做：
 * <ol>
 *   <li><b>服务端可信输入</b>：{@code AgentContext} 的每一项都从 run 行与账户行取，
 *       问题文本从 chat_message 取——后台线程没有请求上下文，任何"从 ThreadLocal 摸身份"的写法
 *       在这里都会变成读到别人的数据。</li>
 *   <li><b>结果必须落库</b>：完整 {@link AgentAnswer} 写进 {@code agent_run.result_payload}，
 *       页面重进读同一份快照；{@code agent_step} 的截断摘要不足以恢复结果，也不冒充结果。</li>
 *   <li><b>终态只能写一次</b>：{@code finishCas} 的 WHERE 限定仍在 QUEUED/RUNNING，
 *       被取消的迟到 Worker 不会覆盖已写好的终态。</li>
 * </ol>
 */
@Service
public class RunExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";

    private final com.hfj.fitagent.agent.core.AgentRunner runner;
    private final AgentRunMapper runMapper;
    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final ModelCallMapper modelCallMapper;
    private final CoachAccountingReadMapper accountingMapper;
    private final ProviderCatalogService catalogService;
    private final BudgetPolicyService budgetPolicyService;
    private final CoachDataRevisionReader revisionReader;
    private final ToolRegistry toolRegistry;
    private final com.hfj.fitagent.modules.coach.support.AgentStepWriter stepWriter;
    private final CoachMessageWriter messages;
    private final RunCancellationRegistry cancellations;
    private final RunLimits limits;
    private final CoachProperties properties;
    private final ObjectMapper objectMapper;
    private final AppClock clock;

    public RunExecutionService(com.hfj.fitagent.agent.core.AgentRunner runner,
                               AgentRunMapper runMapper,
                               ChatConversationMapper conversationMapper,
                               ChatMessageMapper messageMapper,
                               ModelCallMapper modelCallMapper,
                               CoachAccountingReadMapper accountingMapper,
                               ProviderCatalogService catalogService,
                               BudgetPolicyService budgetPolicyService,
                               CoachDataRevisionReader revisionReader,
                               ToolRegistry toolRegistry,
                               com.hfj.fitagent.modules.coach.support.AgentStepWriter stepWriter,
                               CoachMessageWriter messages,
                               RunCancellationRegistry cancellations,
                               RunLimits limits,
                               CoachProperties properties,
                               ObjectMapper objectMapper,
                               AppClock clock) {
        this.runner = runner;
        this.runMapper = runMapper;
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.modelCallMapper = modelCallMapper;
        this.accountingMapper = accountingMapper;
        this.catalogService = catalogService;
        this.budgetPolicyService = budgetPolicyService;
        this.revisionReader = revisionReader;
        this.toolRegistry = toolRegistry;
        this.stepWriter = stepWriter;
        this.messages = messages;
        this.cancellations = cancellations;
        this.limits = limits;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock == null ? new AppClock() : clock;
    }

    /**
     * 领取并执行一个 run。
     *
     * @return {@code true} 表示本线程确实跑了这个 run；{@code false} 表示没抢到或状态已变，调用方什么都不做
     */
    public boolean execute(long runId, String workerId) {
        AgentRunEntity run = runMapper.selectById(runId);
        if (run == null || !"QUEUED".equals(run.getStatus())) {
            return false;
        }
        Instant startedAt = clock.now();
        Instant leaseExpiresAt = startedAt.plusSeconds(properties.worker().leaseSeconds());
        if (runMapper.claim(runId, workerId, startedAt, leaseExpiresAt) == 0) {
            // 0 行 = 已被别的 Worker 领走 / 已取消 / 已是终态：绝不重试，也不把自己的结果写回去
            return false;
        }
        RunCancellation cancellation = cancellations.register(runId);
        try {
            return runClaimed(run, workerId, startedAt, cancellation);
        } finally {
            cancellations.release(runId);
        }
    }

    private boolean runClaimed(AgentRunEntity run, String workerId, Instant startedAt,
                               RunCancellation cancellation) {
        long userId = run.getUserId();
        ChatMessageEntity question = findQuestion(run);
        ChatConversationEntity conversation = conversationMapper.selectOne(new QueryWrapper<ChatConversationEntity>()
                .eq("id", run.getConversationId()).eq("user_id", userId));
        if (question == null || conversation == null) {
            // run 与它的问题/会话是同一事务写入的，走到这里说明数据被外部改动过：按失败收尾，不猜内容
            log.error("run 缺少可执行的问题或会话 runId={} runUserId={}", run.getId(), userId);
            finish(run, unavailableAnswer("本次运行的问题文本或会话已不可用，未发起模型调用。",
                    StopReason.PROTOCOL_INCOMPLETE), "RUN_INPUT_MISSING", false);
            return true;
        }

        EvidenceRegistry evidence = new EvidenceRegistry();
        TraceRecorder trace = TraceRecorder.forRun(stepWriter.forRun(userId, run.getId()), clock);
        AgentAnswer answer;
        try {
            AgentContext context = buildContext(run, userId, startedAt);
            List<com.hfj.fitagent.agent.context.PriorTurn> history = historyBefore(run, userId, question);
            answer = runner.run(new RunRequest(context, question.getContentText(), history, cancellation,
                    trace, evidence));
        } catch (RuntimeException e) {
            // 上下文装配失败（模型/凭据/策略被删）与循环内部异常都走同一条确定性收尾：
            // 不写 assistant 消息、不让一次内部失败伪装成一段回答内容，费用按台账的实际值显示
            log.error("run 装配或执行失败 runId={} type={}", run.getId(), e.getClass().getSimpleName());
            finish(run, unavailableAnswer("本次运行在准备阶段失败，未宣称任何个人数据结论。",
                    StopReason.PROVIDER_ERROR), ErrorCode.MODEL_NOT_CONFIGURED.name(), false);
            return true;
        }
        finish(run, answer, null, true);
        return true;
    }

    /** 服务端可信上下文的唯一组装点：后台线程没有任何请求作用域可以依赖。 */
    private AgentContext buildContext(AgentRunEntity run, long userId, Instant startedAt) {
        ZoneId zone = revisionReader.zoneOf(userId);
        LocalDate businessDate = clock.businessDate(zone);
        ProviderRef provider = catalogService.requireProvider(properties.providerCode());
        com.hfj.fitagent.modules.modelaccess.api.ModelSelection selected =
                catalogService.loadSelection(provider, run.getModelId());
        BudgetPolicyEntity policy = budgetPolicyService.requirePolicy(userId, provider.providerId(),
                run.getCurrency());

        ModelSelection model = new ModelSelection(provider.providerCode(), selected.modelId(), selected.modelCode(),
                new ModelCapabilities(selected.supportsTools(), selected.supportsVision(), selected.supportsJson()),
                selected.maxOutputTokens());
        CredentialHandle credential = new CredentialHandle(run.getCredentialId(), run.getCredentialVersion(),
                provider.providerCode());
        BudgetReference budget = new BudgetReference(policy == null ? null : policy.getId(), null,
                run.getCurrency());
        Instant deadline = startedAt.plusSeconds(limits.deadlineSeconds());

        return new AgentContext(run.getId(), run.getRunUuid(), userId, run.getConversationId(), zone,
                businessDate, run.getContextRevision() == null
                        ? com.hfj.fitagent.agent.core.DataRevisionReader.UNKNOWN : run.getContextRevision(),
                credential, model, budget, deadline, toolRegistry.names());
    }

    private ChatMessageEntity findQuestion(AgentRunEntity run) {
        List<ChatMessageEntity> rows = messageMapper.selectList(new QueryWrapper<ChatMessageEntity>()
                .eq("user_id", run.getUserId())
                .eq("run_id", run.getId())
                .eq("role", ROLE_USER)
                .orderByAsc("message_no"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 历史回放只取"user + 最终可见 assistant"，并且<b>排除本次 run 自己的消息</b>（§8）。
     * 历史里的模型回答是非权威文本：它不能替代工具，也不能改变权限。
     */
    private List<com.hfj.fitagent.agent.context.PriorTurn> historyBefore(AgentRunEntity run, long userId,
                                                                        ChatMessageEntity question) {
        List<ChatMessageEntity> rows = messageMapper.selectList(new QueryWrapper<ChatMessageEntity>()
                .eq("user_id", userId)
                .eq("conversation_id", run.getConversationId())
                .in("role", List.of(ROLE_USER, ROLE_ASSISTANT))
                .lt("message_no", question.getMessageNo())
                .orderByAsc("message_no"));
        List<com.hfj.fitagent.agent.context.PriorTurn> turns = new ArrayList<>();
        String pendingUser = null;
        for (ChatMessageEntity row : rows) {
            if (ROLE_USER.equals(row.getRole())) {
                pendingUser = row.getContentText();
            } else if (pendingUser != null) {
                turns.add(new com.hfj.fitagent.agent.context.PriorTurn(pendingUser, row.getContentText()));
                pendingUser = null;
            }
        }
        int max = properties.historyTurns();
        return turns.size() <= max ? turns : List.copyOf(turns.subList(turns.size() - max, turns.size()));
    }

    // ---- 收尾 ----

    /**
     * 终态落定：一次 CAS，只写一次。
     *
     * @param writeMessage 只有真正跑完一轮才写 assistant 消息。装配阶段就失败的 run 如果也写一条
     *                     "我失败了"进会话历史，下一轮它会作为历史文本被模型读到——那是把内部错误
     *                     伪装成回答内容（§8：历史回答是非权威文本，越少伪装越好）。
     */
    private void finish(AgentRunEntity run, AgentAnswer answer, String errorCode, boolean writeMessage) {
        long userId = run.getUserId();
        long runId = run.getId();
        BigDecimal settled = modelCallMapper.sumSettledCost(userId, runId);
        BigDecimal uncertain = accountingMapper.sumUncertainCommitted(userId, runId);
        Instant finishedAt = clock.now();

        int updated = runMapper.finishCas(runId, userId, answer.runStatus(),
                CoachStopReasons.toDatabase(answer.stopReason() == null ? null : answer.stopReason().name()),
                errorCode, serialize(answer), settled, uncertain, finishedAt);
        if (updated == 0) {
            // 终态已经被取消接口写好：迟到的结果不能覆盖它，费用与事实以库里已有的那一行为准
            log.info("run 已进入终态，本次迟到结果不覆盖 runId={} status={}", runId, answer.runStatus());
            return;
        }
        if (!writeMessage) {
            return;
        }

        messages.append(userId, run.getConversationId(), runId, ROLE_ASSISTANT, answer.answerText(),
                factCards(answer), answer.evidenceRefs(), answer.missingFields(),
                answer.dataRevision() == null ? run.getContextRevision() : answer.dataRevision(),
                answer.isStale(), answer.stopReason() != null && answer.stopReason().successful());
        messages.touch(userId, run.getConversationId(), null, finishedAt);
        log.info("run 收尾完成 runId={} status={} stop={} modelCalls={} toolCalls={}",
                runId, answer.runStatus(), answer.stopReason(),
                answer.usageSummary().modelCalls(), answer.usageSummary().toolCalls());
    }

    private String serialize(AgentAnswer answer) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("answerText", answer.answerText());
            payload.put("factCards", answer.factCards().stream().map(this::factCardMap).toList());
            payload.put("evidenceRefs", answer.evidenceRefs());
            payload.put("missingFields", answer.missingFields());
            payload.put("dataRevision", answer.dataRevision());
            payload.put("isStale", answer.isStale());
            payload.put("runStatus", answer.runStatus());
            payload.put("stopReason", CoachStopReasons.toWire(
                    answer.stopReason() == null ? null : answer.stopReason().name()));
            payload.put("contextTruncated", answer.contextTruncated());
            // 不放 costKnown / costUncertain：§11.1 第 1 条把费用字段唯一地放在 run 响应上。
            // 两处都能放就一定漂移（快照里的布尔与台账里的金额会各说一套）。
            payload.put("usage", Map.of("modelCalls", answer.usageSummary().modelCalls(),
                    "toolCalls", answer.usageSummary().toolCalls()));
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            log.warn("结果快照序列化失败，run 仍按终态收尾 runId? type={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private Map<String, Object> factCardMap(FactCard card) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("evidenceId", card.evidenceId());
        out.put("label", card.label());
        out.put("value", card.value());
        out.put("unit", card.unit());
        out.put("sourceTable", card.sourceTable());
        out.put("sourceId", card.sourceId());
        out.put("asOfDate", card.asOfDate() == null ? null : card.asOfDate().toString());
        out.put("ruleVersion", card.ruleVersion());
        out.put("dataRevision", card.dataRevision());
        return out;
    }

    private List<Map<String, Object>> factCards(AgentAnswer answer) {
        return answer.factCards().stream().map(this::factCardMap).toList();
    }

    /** 没有任何可核实事实的收尾：自由文本只说明"为什么没结论"，不编造个人数据，也不冒充 0 元。 */
    private AgentAnswer unavailableAnswer(String text, StopReason reason) {
        return AgentAnswer.of(List.of(), List.of(), List.of("本轮未取得任何可核实的个人数据"), null, reason,
                com.hfj.fitagent.agent.answer.UsageSummary.empty(), true, false, false, false, text);
    }
}
