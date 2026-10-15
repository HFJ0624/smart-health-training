package com.hfj.fitagent.agent.core;

import com.hfj.fitagent.agent.answer.AgentAnswer;
import com.hfj.fitagent.agent.answer.AnswerAssembler;
import com.hfj.fitagent.agent.answer.UsageSummary;
import com.hfj.fitagent.agent.context.ContextManager;
import com.hfj.fitagent.agent.context.ConversationContext;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.model.ThinkingMode;
import com.hfj.fitagent.agent.model.ToolCall;
import com.hfj.fitagent.agent.model.ToolChoice;
import com.hfj.fitagent.agent.tool.ExecutedTool;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolExecutor;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.agent.tool.ToolResultCache;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import com.hfj.fitagent.agent.trace.TraceEventType;
import com.hfj.fitagent.agent.trace.TraceRecorder;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.common.AppClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 自研执行循环（docs/agent-v1-design.md §4）。这个循环就是本项目存在的理由，所以<b>不</b>引入任何编排框架：
 * 预算闸门的位置、配对的顺序、停止的口径、"不重发"的承诺都必须能被逐条审查。
 *
 * <pre>
 * while (还有模型调用额度 &amp;&amp; 未截止 &amp;&amp; 未取消)
 *     gateway.call(...)          ← 每次远程调用都过预算闸门；不在数据库锁里等 HTTP
 *     finish_reason 不可用        → PROTOCOL_INCOMPLETE，保留已验证事实，<b>不执行被截断的 arguments</b>
 *     有 tool_calls              → assistant(含 tool_calls) 原样入上下文
 *                                → 顺序执行：白名单 → 注册表 → 严格解析校验 → 执行 → 限长
 *                                → 为<b>每个</b>声明过的 tool_call_id 追加恰好一条 tool 消息（错误也追加）
 *                                → 全部配对完成后才进入下一轮
 *     否则                      → AnswerAssembler 校验引用并组装 → 返回
 * 最后一次允许的调用带 tool_choice=none 要求收束
 * 预算/时间耗尽 → 不额外发"免费总结"请求，用确定性模板返回已验证事实 + 停止原因
 * </pre>
 *
 * <p>三条附加行为（design §4 细节决策）：
 * <ul>
 *   <li><b>同 run 结果复用</b>：{@code (toolName, 规范化参数, dataRevision)} 命中缓存时不再读库，
 *       但新的 tool_call_id 仍然得到自己的 tool 消息；<b>连续两轮</b>请求同一个键 → {@code REPETITIVE_LOOP}
 *       终止——缓存绝不能变成让模型空转烧钱的许可。</li>
 *   <li><b>相同请求重复发出</b>（按请求规范形状精确比对）→ {@code REPETITIVE_LOOP}，不发出这一次调用。</li>
 *   <li><b>参数修正最多一次</b>，并且照常计入调用次数与费用；第二次仍非法就停止。</li>
 * </ul>
 */
@Component
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);

    /** 本 run 允许因参数非法而追加的"修正轮"次数（design §4：有界修正 1 次，计入原 run 的次数与费用）。 */
    private static final int MAX_ARGUMENT_REPAIR_ROUNDS = 1;

    private final ContextManager contextManager;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ModelCallGateway gateway;
    private final RunLimits limits;
    private final AnswerAssembler answerAssembler;
    private final AppClock clock;
    /** 可空：没有实现时不宣称陈旧，只在结果里说明未核对修订号。 */
    private final DataRevisionReader dataRevisionReader;

    @Autowired
    public AgentRunner(ContextManager contextManager,
                       ToolRegistry toolRegistry,
                       ToolExecutor toolExecutor,
                       ModelCallGateway gateway,
                       RunLimits limits,
                       AnswerAssembler answerAssembler,
                       AppClock clock,
                       ObjectProvider<DataRevisionReader> dataRevisionReader) {
        this(contextManager, toolRegistry, toolExecutor, gateway, limits, answerAssembler, clock,
                dataRevisionReader == null ? null : dataRevisionReader.getIfAvailable());
    }

    /** 测试与显式装配用。 */
    public AgentRunner(ContextManager contextManager,
                       ToolRegistry toolRegistry,
                       ToolExecutor toolExecutor,
                       ModelCallGateway gateway,
                       RunLimits limits,
                       AnswerAssembler answerAssembler,
                       AppClock clock,
                       DataRevisionReader dataRevisionReader) {
        this.contextManager = contextManager;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.gateway = gateway;
        this.limits = limits == null ? RunLimits.defaults() : limits;
        this.answerAssembler = answerAssembler;
        this.clock = clock == null ? new AppClock() : clock;
        this.dataRevisionReader = dataRevisionReader;
    }

    public AgentAnswer run(RunRequest request) {
        AgentContext ctx = request.context();
        TraceRecorder trace = request.trace();
        EvidenceRegistry registry = request.evidenceRegistry();
        RunCancellation cancellation = request.cancellation();
        Ledger ledger = new Ledger();

        if (!ctx.hasCredential()) {
            // 一次远程调用都不发起：没有凭据就没有"预占→HTTP→结算"这条路
            return stopped(ctx, registry, trace, ledger, 0, 0, UsageSummary.empty(),
                    StopReason.NO_CREDENTIAL, "本次运行没有绑定可用的模型凭据，未发起任何模型调用。", false);
        }

        ConversationContext conversation = contextManager.open(ctx, request.question(), request.history());
        if (conversation.contextTruncated()) {
            trace.record(TraceEventType.CONTEXT_TRUNCATED, null, null, null,
                    "更早的对话轮次已按轮次边界丢弃", "SUCCEEDED", null, null);
        }

        ToolResultCache cache = new ToolResultCache();
        Set<String> sentRequestShapes = new HashSet<>();
        Set<String> previousRoundKeys = Set.of();

        int modelCalls = 0;
        int toolCalls = 0;
        int repairRounds = 0;
        StopReason stopReason = StopReason.LOOP_EXHAUSTED;
        String stopDetail = "已达到本轮允许的模型调用次数上限，仍未拿到可用回答。";

        while (true) {
            if (cancellation.isCancelled()) {
                stopReason = StopReason.CANCELLED;
                stopDetail = "本轮被取消；已发生的调用不因此免费。";
                break;
            }
            if (ctx.deadlinePassed(clock.now())) {
                stopReason = StopReason.DEADLINE_STOPPED;
                stopDetail = "run 截止时间已到（工程闸门，不是健康结论）。";
                break;
            }
            if (!limits.modelCallAllowed(modelCalls)) {
                stopReason = StopReason.LOOP_EXHAUSTED;
                stopDetail = "已达到本轮允许的模型调用次数上限 " + limits.maxModelCalls() + "，未再发起调用。";
                break;
            }

            List<ToolDefinition> toolDefinitions = declaredTools(ctx);
            boolean toolsDeclared = !toolDefinitions.isEmpty();
            ToolChoice choice = decideToolChoice(toolsDeclared, modelCalls);

            ModelRequest modelRequest;
            try {
                conversation.assertProtocolValid();
                modelRequest = new ModelRequest(ctx.model().modelCode(), conversation.messages(), toolDefinitions,
                        choice, ThinkingMode.DISABLED,
                        limits.effectiveMaxOutputTokens(ctx.model().maxOutputTokens()), null,
                        ctx.remaining(clock.now()));
            } catch (AgentProtocolException e) {
                stopReason = StopReason.PROTOCOL_INCOMPLETE;
                stopDetail = "上下文工具配对非法，请求未发出：" + e.getMessage();
                log.warn("上下文配对失败 runId={} detail={}", ctx.runId(), e.getMessage());
                break;
            }

            if (!sentRequestShapes.add(modelRequest.canonicalShape())) {
                // 空转检测：同样的消息序列再问一次不会有新信息，只会再烧一次预算
                stopReason = StopReason.REPETITIVE_LOOP;
                stopDetail = "检测到与上一轮完全相同的请求，已停止（缓存不应让模型空转）。";
                break;
            }

            modelCalls = modelCalls + 1;
            Instant callStarted = clock.now();
            ModelCallOutcome outcome = gateway.call(new ModelCallCommand(ctx, modelRequest, cancellation, modelCalls));
            long callMillis = Duration.between(callStarted, clock.now()).toMillis();
            ledger.record(outcome);
            trace.record(TraceEventType.MODEL_CALL, null, null,
                    "model=" + modelRequest.model() + ", messages=" + modelRequest.messages().size()
                            + ", tools=" + toolDefinitions.size() + ", toolChoice=" + choice,
                    describe(outcome), outcome.status().name(), callMillis, outcome.errorCode());

            Optional<StopReason> stopping = stopReasonFor(outcome.status());
            if (stopping.isPresent()) {
                stopReason = stopping.get();
                stopDetail = detailFor(outcome);
                break;
            }

            ModelResponse response = outcome.response();
            if (!FinishReason.isAccepted(response.finishReason())) {
                // length / content_filter / insufficient_system_resource / aborted / 未识别值：
                // 内容不可信，被截断的 arguments 一律不执行（protocol §4）
                stopReason = StopReason.PROTOCOL_INCOMPLETE;
                stopDetail = "供应商返回 finish_reason=" + response.finishReason()
                        + "，内容按不完整处理，已保留本轮此前核实的事实。";
                break;
            }

            if (response.hasToolCalls() && !response.finishReason().isUsableAnswer()) {
                if (!response.isToolCallRound()) {
                    stopReason = StopReason.PROTOCOL_INCOMPLETE;
                    stopDetail = "工具调用被截断，arguments 未执行。";
                    break;
                }
                if (choice == ToolChoice.NONE) {
                    // 供应商没遵守收尾约束：仍然把这一轮配对补齐（不留下无法回传的工具组），
                    // 然后由循环顶部的次数闸门以 LOOP_EXHAUSTED 结束。丢弃这些已付费的读取结果
                    // 换不来任何安全性，只会让用户拿不到事实卡片。
                    log.warn("收尾调用（tool_choice=none）仍返回 tool_calls，已按普通轮配对执行 runId={}",
                            ctx.runId());
                }
                conversation.appendAssistant(ModelMessage.assistantToolCalls(response.content(), response.toolCalls()));
                RoundOutcome round = executeRound(response.toolCalls(), ctx, conversation, registry, cache,
                        previousRoundKeys, cancellation, trace, toolCalls);
                toolCalls += round.executed();
                previousRoundKeys = round.keys();
                if (round.stopReason() != null) {
                    stopReason = round.stopReason();
                    stopDetail = round.detail();
                    break;
                }
                if (round.argumentErrors()) {
                    if (repairRounds >= MAX_ARGUMENT_REPAIR_ROUNDS) {
                        stopReason = StopReason.PROTOCOL_INCOMPLETE;
                        stopDetail = "参数仍有非法项，但本 run 的唯一一次有界修正已用尽，停止继续请求。";
                        break;
                    }
                    repairRounds = repairRounds + 1;
                    log.info("参数非法触发唯一一次有界修正轮 runId={} attempt={}", ctx.runId(), modelCalls);
                }
                continue;
            }

            // finish_reason=stop 且没有可执行的工具轮：唯一可以宣称成功的出口
            if (response.hasToolCalls()) {
                // 自然停止却带着 tool_calls：不执行它们（执行了就没有下一次调用可以回传结果，会留下未闭合的工具组）
                log.warn("finish_reason=stop 但同时声明了 tool_calls，按可用回答处理且不调用工具 runId={}", ctx.runId());
            }
            UsageSummary usage = ledger.summarize(modelCalls, toolCalls);
            boolean contextTruncated = conversation.contextTruncated();
            AgentAnswer answer = answerAssembler.validateAndBuild(ctx, response, registry, usage,
                    StopReason.SUCCEEDED, ledger.costKnown(), ledger.costUncertain(), isStale(ctx), contextTruncated);
            trace.record(TraceEventType.ANSWER, null, null, null,
                    "factCards=" + answer.factCards().size() + ", evidence=" + answer.evidenceRefs().size()
                            + ", missing=" + answer.missingFields().size(),
                    "SUCCEEDED", null, null);
            trace.record(TraceEventType.RUN_STOPPED, null, null, null, describeRun(answer), "SUCCEEDED", null, null);
            return answer;
        }

        UsageSummary usage = ledger.summarize(modelCalls, toolCalls);
        return stopped(ctx, registry, trace, ledger, modelCalls, toolCalls, usage, stopReason, stopDetail,
                conversation.contextTruncated());
    }

    // ---- 工具轮 ----

    private RoundOutcome executeRound(List<ToolCall> calls,
                                      AgentContext ctx,
                                      ConversationContext conversation,
                                      EvidenceRegistry registry,
                                      ToolResultCache cache,
                                      Set<String> previousRoundKeys,
                                      RunCancellation cancellation,
                                      TraceRecorder trace,
                                      int toolCallsAlreadyTotal) {
        Set<String> roundKeys = new LinkedHashSet<>();
        int inRound = 0;
        int executed = 0;
        boolean argumentErrors = false;

        for (ToolCall call : calls) {
            if (cancellation.isCancelled()) {
                return new RoundOutcome(executed, roundKeys, StopReason.CANCELLED,
                        "本轮被取消；已发生的调用不因此免费。", argumentErrors);
            }
            if (!limits.toolCallAllowed(toolCallsAlreadyTotal + executed)) {
                return new RoundOutcome(executed, roundKeys, StopReason.LIMIT_STOPPED,
                        "已达到本轮允许的工具调用次数上限 " + limits.maxToolCalls() + "。", argumentErrors);
            }
            if (!limits.toolAllowedInRound(inRound)) {
                return new RoundOutcome(executed, roundKeys, StopReason.LIMIT_STOPPED,
                        "单轮工具调用数超过上限 " + limits.maxToolsPerRound() + "。", argumentErrors);
            }

            String canonicalArguments = ToolExecutor.canonicalize(call.argumentsJson());
            String cacheKey = call.name() + "|" + canonicalArguments + "|" + ctx.dataRevision();
            boolean repeatsPreviousRound = previousRoundKeys.contains(cacheKey);

            trace.record(TraceEventType.TOOL_CALL, call.name(), call.id(), call.argumentsJson(),
                    null, "DECLARED", null, null);

            ExecutedTool outcome = toolExecutor.execute(call, ctx, cache);
            executed++;
            inRound++;
            roundKeys.add(cacheKey);
            registry.registerAll(outcome.result(), outcome.toolName(), outcome.dataSnapshot());
            // 每个声明过的 tool_call_id 恰好一条 tool 消息（失败也回传结构化错误），全部配对后才进下一轮
            conversation.appendTool(ModelMessage.tool(call.id(), call.name(), outcome.modelPayload()));

            trace.record(TraceEventType.TOOL_RESULT, call.name(), call.id(), call.argumentsJson(),
                    "code=" + outcome.result().code() + ", dataComplete=" + outcome.result().dataComplete()
                            + ", truncated=" + outcome.result().truncated()
                            + ", fromCache=" + outcome.fromCache(),
                    outcome.successful() ? "SUCCEEDED" : "FAILED", outcome.durationMs(), outcome.errorCode());

            if (!outcome.successful() && isArgumentFailure(outcome.errorCode())) {
                argumentErrors = true;
            }
            if (repeatsPreviousRound) {
                // 连续两轮读同一个键：模型在原地打转。配对已经补齐，所以在这里停止不会留下非法上下文。
                return new RoundOutcome(executed, roundKeys, StopReason.REPETITIVE_LOOP,
                        "连续两轮请求同一份工具读取（" + call.name() + "），已停止以免空转烧预算。", argumentErrors);
            }
        }
        return new RoundOutcome(executed, roundKeys, null, null, argumentErrors);
    }

    private static boolean isArgumentFailure(String code) {
        return ToolErrorCode.INVALID_ARGUMENTS.equals(code) || ToolErrorCode.VALIDATION_FAILED.equals(code);
    }

    // ---- 请求构造 ----

    /** 只把白名单里真实注册的工具声明发出去：本轮不注册的能力连"被模型想到"的机会都不给。 */
    private List<ToolDefinition> declaredTools(AgentContext ctx) {
        if (!ctx.model().capabilities().supportsTools() || ctx.allowedTools().isEmpty()) {
            return List.of();
        }
        List<ToolDefinition> definitions = toolRegistry.definitionsFor(ctx.allowedTools());
        List<String> unknown = toolRegistry.unknownAllowedNames(ctx.allowedTools());
        if (!unknown.isEmpty()) {
            log.warn("白名单里有未注册的工具名，已忽略 runId={} names={}", ctx.runId(), unknown);
        }
        return definitions;
    }

    /**
     * 最后一次允许的调用一律 {@code NONE}：本轮没有下一次调用的额度了，
     * 再收一个 tool_calls 就等于用一次"无法回传结果"的死信。强制收束能至少拿到一份可用回答。
     * （{@code required} 与指定工具名在关闭思考后仍不放开——protocol §3、§5.3）
     */
    private ToolChoice decideToolChoice(boolean toolsDeclared, int modelCallsAlready) {
        if (!toolsDeclared) {
            return null;
        }
        return limits.isLastAllowedCall(modelCallsAlready) ? ToolChoice.NONE : ToolChoice.AUTO;
    }

    // ---- 状态映射 ----

    private static Optional<StopReason> stopReasonFor(ModelCallStatus status) {
        return switch (status) {
            case SUCCEEDED -> Optional.empty();
            case BUDGET_STOPPED -> Optional.of(StopReason.BUDGET_STOPPED);
            case NO_CREDENTIAL -> Optional.of(StopReason.NO_CREDENTIAL);
            case CANCELLED -> Optional.of(StopReason.CANCELLED);
            // 401/402/429/500/503/结果未知 全部归为供应商侧失败；具体区别由 errorCode 表达，
            // 这样界面能区分"我们的闸门"（LIMIT/BUDGET/DEADLINE）与"供应商的状态"
            case CREDENTIAL_INVALID, INSUFFICIENT_BALANCE, RATE_LIMITED, PROVIDER_UNAVAILABLE, PROVIDER_ERROR,
                 RESULT_UNKNOWN -> Optional.of(StopReason.PROVIDER_ERROR);
        };
    }

    private static String detailFor(ModelCallOutcome outcome) {
        String code = outcome.errorCode() == null ? outcome.status().name() : outcome.errorCode();
        String detail = outcome.safeDetail() == null ? "" : "：" + outcome.safeDetail();
        return "供应商/预算状态：" + code + detail
                + (outcome.requestSent() ? "（请求已发出，费用可能存在）" : "（请求未发出，无新增费用）");
    }

    private static String describe(ModelCallOutcome outcome) {
        ModelResponse response = outcome.response();
        if (response == null) {
            return "status=" + outcome.status() + ", code=" + outcome.errorCode();
        }
        return "finishReason=" + response.finishReason() + ", toolCalls=" + response.toolCalls().size()
                + ", contentChars=" + response.content().length();
    }

    private static String describeRun(AgentAnswer answer) {
        return "stopReason=" + answer.stopReason() + ", isStale=" + answer.isStale()
                + ", costUncertain=" + answer.costUncertain();
    }

    // ---- 停止出口 ----

    private AgentAnswer stopped(AgentContext ctx,
                               EvidenceRegistry registry,
                               TraceRecorder trace,
                               Ledger ledger,
                               int modelCalls,
                               int toolCalls,
                               UsageSummary usage,
                               StopReason stopReason,
                               String detail,
                               boolean contextTruncated) {
        AgentAnswer answer = answerAssembler.stoppedAnswer(ctx, registry, usage, stopReason,
                ledger.costKnown(), ledger.costUncertain(), isStale(ctx), contextTruncated, detail);
        trace.record(TraceEventType.RUN_STOPPED, null, null, null,
                stopReason.name() + " / modelCalls=" + modelCalls + " toolCalls=" + toolCalls,
                stopReason.runStatus(), null, stopReason.name());
        return answer;
    }

    /**
     * run 开始与结束各读一次数据修订号（design §9）。
     *
     * <p>不同就 {@code isStale=true}：事实保留原快照与来源日期，<b>不</b>自动付费重跑。
     * 没有注入读取端口时返回 false 并在轨迹里说明"未核对"——不假装做过核对。
     */
    private boolean isStale(AgentContext ctx) {
        if (dataRevisionReader == null) {
            log.debug("未注入 DataRevisionReader，run 结束时的修订号未核对 runId={}", ctx.runId());
            return false;
        }
        long endRevision;
        try {
            endRevision = dataRevisionReader.currentRevision(ctx);
        } catch (RuntimeException e) {
            log.warn("修订号读取失败，按未核对处理 type={}", e.getClass().getSimpleName());
            return false;
        }
        if (endRevision == DataRevisionReader.UNKNOWN) {
            return false;
        }
        return endRevision != ctx.dataRevision();
    }

    /** 一轮工具执行的产出。 */
    private record RoundOutcome(int executed, Set<String> keys, StopReason stopReason, String detail,
                                boolean argumentErrors) {
    }

    /** 本 run 的用量与费用确定性累计。 */
    private static final class Ledger {

        private final List<ModelUsage> usages = new ArrayList<>();
        private boolean costUncertain;
        private boolean everyUsageKnown = true;

        void record(ModelCallOutcome outcome) {
            if (outcome == null) {
                return;
            }
            if (outcome.requestSent() && outcome.status() != ModelCallStatus.SUCCEEDED) {
                // 已送达但没有可用结果 → 这一笔可能已经计费，必须保留"不确定"而不是归零
                costUncertain = true;
                everyUsageKnown = false;
            }
            if (outcome.response() == null) {
                if (outcome.requestSent()) {
                    everyUsageKnown = false;
                }
                return;
            }
            usages.add(outcome.response().usage());
            if (!outcome.response().usage().hasAnyKnown()) {
                everyUsageKnown = false;
                costUncertain = true;
            }
        }

        UsageSummary summarize(int modelCalls, int toolCalls) {
            return UsageSummary.of(usages, modelCalls, toolCalls);
        }

        boolean costKnown() {
            return everyUsageKnown && !costUncertain;
        }

        boolean costUncertain() {
            return costUncertain;
        }
    }
}
