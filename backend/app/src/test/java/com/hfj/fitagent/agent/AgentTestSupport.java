package com.hfj.fitagent.agent;

import com.hfj.fitagent.agent.answer.AnswerAssembler;
import com.hfj.fitagent.agent.answer.CitationValidator;
import com.hfj.fitagent.agent.context.ContextBudget;
import com.hfj.fitagent.agent.context.ContextManager;
import com.hfj.fitagent.agent.context.CoachPromptTemplates;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.AgentRunner;
import com.hfj.fitagent.agent.core.BudgetReference;
import com.hfj.fitagent.agent.core.CredentialHandle;
import com.hfj.fitagent.agent.core.ModelCallCommand;
import com.hfj.fitagent.agent.core.ModelCallGateway;
import com.hfj.fitagent.agent.core.ModelCallOutcome;
import com.hfj.fitagent.agent.core.ModelCallStatus;
import com.hfj.fitagent.agent.core.ModelSelection;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.core.RunRequest;
import com.hfj.fitagent.agent.fake.FakeModelClient;
import com.hfj.fitagent.agent.fake.FakeModelProperties;
import com.hfj.fitagent.agent.model.ModelCapabilities;
import com.hfj.fitagent.agent.model.ModelProviderException;
import com.hfj.fitagent.agent.model.ModelRequest;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.tool.AgentTool;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.agent.tool.ToolExecutor;
import com.hfj.fitagent.agent.tool.ToolRegistry;
import com.hfj.fitagent.agent.tool.ToolResult;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import com.hfj.fitagent.agent.trace.TraceRecorder;
import com.hfj.fitagent.common.AppClock;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Positive;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Agent 测试夹具：不启 Spring 上下文、不连数据库、不起 Docker、不发网络。
 *
 * <p>时间源固定（{@code 2026-10-13T02:00Z}，业务日 Asia/Shanghai = 2026-10-13），
 * Runner / ToolExecutor / DeepSeekClient 都注入同一个 {@link AppClock}——否则"run 剩余时间"
 * 会在两处算出两个答案，工具会被自己的超时闸门误拒。
 */
public final class AgentTestSupport {

    public static final Instant NOW = Instant.parse("2026-10-13T02:00:00Z");
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 13);
    public static final long RUN_ID = 9001L;
    public static final String RUN_UUID = "3f2b1c4a-5d6e-4f70-8a9b-0c1d2e3f4a5b";
    public static final long USER_ID = 42L;
    public static final long CONVERSATION_ID = 77L;
    public static final long DATA_REVISION = 17L;
    public static final String PROVIDER_ID = "DEEPSEEK";
    public static final String MODEL_CODE = "deepseek-flash";
    public static final String FAKE_API_KEY = "sk-test-fake-key-must-never-appear-in-logs";

    private AgentTestSupport() {
    }

    public static AppClock clock() {
        return new AppClock(Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    /** 与 JacksonConfig 一致：未知字段必须失败——工具参数的严格解析依赖这一条。 */
    public static ObjectMapper mapper() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public static Validator validator() {
        return Validation.buildDefaultValidatorFactory().getValidator();
    }

    public static CredentialHandle credential() {
        return new CredentialHandle(5L, 2, PROVIDER_ID);
    }

    public static ModelSelection model() {
        return new ModelSelection(PROVIDER_ID, 3L, MODEL_CODE, ModelCapabilities.toolsOnly(), 4096);
    }

    public static AgentContext context(RunLimits limits) {
        return context(credential(), limits);
    }

    public static AgentContext context(CredentialHandle credential, RunLimits limits) {
        return new AgentContext(RUN_ID, RUN_UUID, USER_ID, CONVERSATION_ID, ZONE, BUSINESS_DATE, DATA_REVISION,
                credential, model(), new BudgetReference(9L, 11L, "CNY"),
                NOW.plusSeconds(limits.deadlineSeconds()), Set.of("get_weight_trend", "get_profile"));
    }

    public static AgentContext contextWithTools(Set<String> allowedTools, RunLimits limits) {
        AgentContext base = context(limits);
        return new AgentContext(base.runId(), base.runUuid(), base.userId(), base.conversationId(), base.zone(),
                base.businessDate(), base.dataRevision(), base.credential(), base.model(), base.budget(),
                base.deadline(), allowedTools);
    }

    public static AgentContext contextNoCredential(RunLimits limits) {
        return context(null, limits);
    }

    /** 模板文本内联，单测不依赖 classpath 资源（资源存在性由 CoachPromptTemplatesTest 单独验证）。 */
    public static CoachPromptTemplates templates() {
        return new CoachPromptTemplates("你是 FitAgent 教练。个人事实必须来自工具。");
    }

    public static ContextManager contextManager() {
        return new ContextManager(templates(), ContextBudget.defaults());
    }

    public static ToolRegistry registry(AgentTool<?, ?>... tools) {
        return new ToolRegistry(List.of(tools), mapper());
    }

    public static ToolExecutor executor(ToolRegistry registry, RunLimits limits) {
        return new ToolExecutor(registry, mapper(), validator(), limits, clock());
    }

    public static AnswerAssembler assembler() {
        return new AnswerAssembler(new CitationValidator(), mapper());
    }

    public static AgentRunner runner(ModelCallGateway gateway, ToolRegistry registry, RunLimits limits) {
        return new AgentRunner(contextManager(), registry, executor(registry, limits), gateway, limits,
                assembler(), clock(), (com.hfj.fitagent.agent.core.DataRevisionReader) null);
    }

    public static AgentRunner runner(ModelCallGateway gateway, ToolRegistry registry, RunLimits limits,
                                     com.hfj.fitagent.agent.core.DataRevisionReader revisionReader) {
        return new AgentRunner(contextManager(), registry, executor(registry, limits), gateway, limits,
                assembler(), clock(), revisionReader);
    }

    public static RunRequest request(AgentContext context, String question, TraceRecorder trace,
                                     EvidenceRegistry registry, RunCancellation cancellation,
                                     List<com.hfj.fitagent.agent.context.PriorTurn> history) {
        return new RunRequest(context, question, history, cancellation, trace, registry);
    }

    public static RunRequest request(AgentContext context, String question) {
        return new RunRequest(context, question, List.of(), new RunCancellation(),
                TraceRecorder.inMemory(), new EvidenceRegistry());
    }

    public static Evidence evidence(String table, String id) {
        return new Evidence(table, id, null, BUSINESS_DATE, null, DATA_REVISION);
    }

    // ---- 测试工具 ----

    public record WeightTrendArgs(@Positive(message = "必须是正数") Integer days) {
    }

    public record WeightTrendData(String latestWeightKg, String unit, List<String> history) {
    }

    /** 模拟 get_weight_trend：真实工具由 modules/coach 实现，这里只提供"有来源、有值"的形状。 */
    public static final class WeightTrendTool implements AgentTool<WeightTrendArgs, WeightTrendData> {

        private int calls;

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_weight_trend", "读取本人最近的体重记录",
                    """
                    {"type":"object","properties":{"days":{"type":"integer","description":"最近多少天"}},\
                    "required":["days"]}""");
        }

        @Override
        public Class<WeightTrendArgs> argumentType() {
            return WeightTrendArgs.class;
        }

        @Override
        public ToolResult<WeightTrendData> execute(WeightTrendArgs args, AgentContext ctx) {
            calls++;
            return ToolResult.ok(new WeightTrendData("71.5", "kg", List.of("72.0", "71.5")),
                    List.of(new Evidence("body_measurement", "501", null, ctx.businessDate(), null,
                            ctx.dataRevision())),
                    ctx.dataRevision());
        }

        public int calls() {
            return calls;
        }
    }

    public record EmptyArgs() {
    }

    public record ProfileData(String heightCm, String trainingLevel) {
    }

    public static final class ProfileTool implements AgentTool<EmptyArgs, ProfileData> {

        private int calls;

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_profile", "读取本人档案与 ACTIVE 目标",
                    "{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public Class<EmptyArgs> argumentType() {
            return EmptyArgs.class;
        }

        @Override
        public ToolResult<ProfileData> execute(EmptyArgs args, AgentContext ctx) {
            calls++;
            return ToolResult.ok(new ProfileData("178", "INTERMEDIATE"),
                    List.of(new Evidence("user_profile", "12", null, ctx.businessDate(), null, ctx.dataRevision())),
                    ctx.dataRevision());
        }

        public int calls() {
            return calls;
        }
    }

    /** 故意慢的工具：验证"超时上限"确实生效（不是把耗时记下来继续当成成功）。 */
    public static final class SleepingTool implements AgentTool<EmptyArgs, String> {

        private final java.time.Duration duration;

        public SleepingTool(java.time.Duration duration) {
            this.duration = duration;
        }

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_slow", "故意慢的工具", "{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public Class<EmptyArgs> argumentType() {
            return EmptyArgs.class;
        }

        @Override
        public java.time.Duration maxDuration() {
            return java.time.Duration.ofMillis(30);
        }

        @Override
        public ToolResult<String> execute(EmptyArgs args, AgentContext ctx) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.error("INTERRUPTED", "执行被中断");
            }
            return ToolResult.ok("太迟了", List.of(), ctx.dataRevision());
        }
    }

    /**
     * 脚本化网关：把 FakeModelClient 包成 {@link ModelCallGateway}，
     * 也就是 modules/billing 需要实现的那层映射（异常 → 结构化状态）。
     *
     * <p>{@code stopAtIndex} 用来模拟"预算闸门在第 n 次调用前拒绝"——Runner 必须因此停止且**不**再发请求。
     */
    public static final class ScriptedGateway implements ModelCallGateway {

        private final FakeModelClient client;
        private final List<ModelRequest> requests = new ArrayList<>();
        private int stopAtIndex = -1;
        private String stopErrorCode = "BUDGET_EXCEEDED_DAILY";

        public ScriptedGateway(FakeModelClient client) {
            this.client = client;
        }

        public ScriptedGateway stopBeforeCall(int index, ModelCallStatus status, String errorCode) {
            this.stopAtIndex = index;
            this.stopErrorCode = errorCode;
            return this;
        }

        public List<ModelRequest> requests() {
            return List.copyOf(requests);
        }

        @Override
        public ModelCallOutcome call(ModelCallCommand command) {
            requests.add(command.request());
            int index = requests.size();
            if (stopAtIndex == index) {
                return ModelCallOutcome.stopped(statusOrBudget(), stopErrorCode, "测试注入：预算闸门拒绝发起本次调用");
            }
            try {
                ModelResponse response = client.complete(command.request(), command.context(), command.cancellation());
                return ModelCallOutcome.succeeded(response);
            } catch (ModelProviderException e) {
                return ModelCallOutcome.failed(mapKind(e.kind()), e.providerErrorCode(), e.getMessage(),
                        e.requestSent());
            }
        }

        private ModelCallStatus statusOrBudget() {
            return ModelCallStatus.BUDGET_STOPPED;
        }

        /** 与 billing 侧必须实现的映射同口径：供应商异常 → 网关状态。 */
        public static ModelCallStatus mapKind(com.hfj.fitagent.agent.model.ProviderErrorKind kind) {
            return switch (kind) {
                case CREDENTIAL_INVALID -> ModelCallStatus.CREDENTIAL_INVALID;
                case CREDENTIAL_MISSING -> ModelCallStatus.NO_CREDENTIAL;
                case INSUFFICIENT_BALANCE -> ModelCallStatus.INSUFFICIENT_BALANCE;
                case RATE_LIMITED -> ModelCallStatus.RATE_LIMITED;
                case INVALID_PARAMS, INVALID_REQUEST, PROVIDER_UNAVAILABLE -> ModelCallStatus.PROVIDER_UNAVAILABLE;
                case RESPONSE_MALFORMED, TRANSPORT_UNKNOWN -> ModelCallStatus.RESULT_UNKNOWN;
                case TRANSPORT_BEFORE_SEND -> ModelCallStatus.PROVIDER_ERROR;
                case PROTOCOL_MISMATCH, CONFIGURATION_REJECTED -> ModelCallStatus.PROVIDER_ERROR;
            };
        }
    }

    public static FakeModelClient fake() {
        return new FakeModelClient(new FakeModelProperties(true, "DEEPSEEK", null));
    }

    public static StandardEnvironment environment(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(profiles.length == 0 ? new String[]{"dev"} : profiles);
        return environment;
    }

    public static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
