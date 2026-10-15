package com.hfj.fitagent.agent.tool;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.model.ToolCall;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具执行器：严格参数校验、结构化错误、字节上限、结果复用。
 *
 * <p>核心立场（docs/agent-v1-design.md §5.1、§9）：模型的 arguments 是不可信输入。
 * 解析失败、未知字段、身份字段、超上限都只有一种处置——<b>不执行</b>，并回传结构化错误让协议仍然闭合。
 */
class ToolExecutorTest {

    private final RunLimits limits = RunLimits.defaults();
    private final AgentContext context = AgentTestSupport.context(limits);

    private ExecutedTool run(ToolCall call, AgentTool<?, ?>... tools) {
        ToolRegistry registry = AgentTestSupport.registry(tools);
        return AgentTestSupport.executor(registry, limits).execute(call, context, new ToolResultCache());
    }

    /** 白名单按需放开：真实 run 的 allowedTools 由服务端算出，测试里不写死成两个工具。 */
    private ExecutedTool runAllowing(ToolCall call, AgentTool<?, ?> tool, String... allowed) {
        return runAllowing(call, tool, limits, allowed);
    }

    private ExecutedTool runAllowing(ToolCall call, AgentTool<?, ?> tool, RunLimits runLimits, String... allowed) {
        AgentContext scoped = AgentTestSupport.contextWithTools(Set.of(allowed), runLimits);
        ToolRegistry registry = AgentTestSupport.registry(tool);
        return AgentTestSupport.executor(registry, runLimits).execute(call, scoped, new ToolResultCache());
    }

    @Test
    @DisplayName("合法调用返回 OK，并把 evidenceId 一起给模型（没有它模型就无可引用之物）")
    void successfulCallCarriesEvidenceIds() {
        ExecutedTool outcome = run(new ToolCall("c1", "get_weight_trend", "{\"days\":7}"),
                new AgentTestSupport.WeightTrendTool());

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.modelPayload()).contains("evidenceId").contains("body_measurement");
        assertThat(outcome.modelPayload()).contains("71.5");
        assertThat(outcome.dataSnapshot()).isNotNull();
    }

    @Test
    @DisplayName("未知字段被拒绝：接口契约里'未定义字段一律拒绝'依赖这一条")
    void unknownArgumentFieldIsRejected() {
        ExecutedTool outcome = run(new ToolCall("c1", "get_weight_trend", "{\"days\":7,\"extra\":1}"),
                new AgentTestSupport.WeightTrendTool());

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(outcome.executed()).isFalse();
    }

    @Test
    @DisplayName("非法 JSON 不执行，错误结构化")
    void malformedJsonIsRejected() {
        ExecutedTool outcome = run(new ToolCall("c1", "get_weight_trend", "{\"days\":"),
                new AgentTestSupport.WeightTrendTool());

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.INVALID_ARGUMENTS);
        assertThat(outcome.modelPayload()).contains(ToolErrorCode.INVALID_ARGUMENTS);
    }

    @Test
    @DisplayName("Bean Validation 违规不执行（只校验单位/正数/长度/枚举/日期格式）")
    void beanValidationIsEnforced() {
        ExecutedTool outcome = run(new ToolCall("c1", "get_weight_trend", "{\"days\":0}"),
                new AgentTestSupport.WeightTrendTool());

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_FAILED);
        assertThat(outcome.modelPayload()).contains("days");
    }

    @Test
    @DisplayName("白名单里有、注册表里没有的工具 → UNKNOWN_TOOL，仍产出可回传的结构化错误")
    void unknownToolIsRejected() {
        // 两层闸门各有各的码：不在白名单 → TOOL_NOT_ALLOWED（见下一个用例）；
        // 白名单放行但注册表没有这个能力（配置错）→ UNKNOWN_TOOL。要走到后者必须显式把它放进白名单。
        ExecutedTool outcome = runAllowing(new ToolCall("c1", "get_workout_records", "{}"),
                new AgentTestSupport.WeightTrendTool(), "get_workout_records");

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.UNKNOWN_TOOL);
        assertThat(outcome.modelPayload()).startsWith("{");
    }

    @Test
    @DisplayName("注册表里有但不在本 run 白名单 → TOOL_NOT_ALLOWED（工具面由服务端决定）")
    void toolOutsideWhitelistIsRejected() {
        AgentContext restricted = AgentTestSupport.contextWithTools(Set.of("get_weight_trend"), limits);
        ToolRegistry registry = AgentTestSupport.registry(new AgentTestSupport.ProfileTool());
        ExecutedTool outcome = AgentTestSupport.executor(registry, limits)
                .execute(new ToolCall("c1", "get_profile", "{}"), restricted, new ToolResultCache());

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_ALLOWED);
    }

    @Test
    @DisplayName("参数里出现身份字段（即使 schema 允许）→ FORBIDDEN_IDENTITY_FIELD，绝不按参数取身份")
    void identityFieldInArgumentsIsRefused() {
        LeakyTool tool = new LeakyTool();
        ExecutedTool outcome = runAllowing(new ToolCall("c1", "get_leaky", "{\"days\":7,\"userId\":999}"),
                tool, "get_leaky");

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.FORBIDDEN_IDENTITY_FIELD);
        assertThat(tool.calls()).as("身份字段出现在参数里就不该执行任何读取").isZero();
    }

    /** 故意写坏的工具：参数里带 userId —— 真实工具不允许这样，本用例验证兜底防线。 */
    public record LeakyArgs(@Positive Integer days, Long userId) {
    }

    public static final class LeakyTool implements AgentTool<LeakyArgs, String> {

        private int calls;

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_leaky", "反例工具", "{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public Class<LeakyArgs> argumentType() {
            return LeakyArgs.class;
        }

        @Override
        public ToolResult<String> execute(LeakyArgs args, AgentContext ctx) {
            calls++;
            return ToolResult.ok("不该被执行", List.of(), ctx.dataRevision());
        }

        public int calls() {
            return calls;
        }
    }

    @Test
    @DisplayName("结果超出字节上限：丢明细并声明 truncated 与 dataComplete=false，仍是合法 JSON")
    void oversizedResultDropsDetailAndDeclaresIncomplete() {

        RunLimits tiny = new RunLimits(4, 8, 4, 200, 1024, 120);
        ExecutedTool outcome = runAllowing(new ToolCall("c1", "get_wide", "{}"), new WideTool(), tiny, "get_wide");

        assertThat(outcome.result().truncated()).isTrue();
        assertThat(outcome.result().dataComplete()).isFalse();
        assertThat(outcome.modelPayload()).doesNotContain("一长串明细");
        assertThat(outcome.modelPayload()).contains("truncated").contains("dataComplete");
        // 进程内的完整快照仍然保留：事实卡片可以按引用取工具提供的值，模型看到的少不等于已验证的少
        assertThat(outcome.dataSnapshot().toString()).contains("一长串明细");
    }

    public record WideData(String detail) {
    }

    static final class WideTool implements AgentTool<AgentTestSupport.EmptyArgs, WideData> {

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_wide", "结果很宽的工具", "{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public Class<AgentTestSupport.EmptyArgs> argumentType() {
            return AgentTestSupport.EmptyArgs.class;
        }

        @Override
        public ToolResult<WideData> execute(AgentTestSupport.EmptyArgs args, AgentContext ctx) {
            return ToolResult.ok(new WideData("一长串明细".repeat(80)), List.of(), ctx.dataRevision());
        }
    }

    @Test
    @DisplayName("工具执行超时 → TIMEOUT，不把它当成一次成功读取")
    void slowToolTimesOut() {
        ExecutedTool outcome = runAllowing(new ToolCall("c1", "get_slow", "{}"),
                new AgentTestSupport.SleepingTool(Duration.ofSeconds(2)), "get_slow");

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.TIMEOUT);
        assertThat(outcome.executed()).isFalse();
        assertThat(outcome.modelPayload()).contains(ToolErrorCode.TIMEOUT);
    }

    @Test
    @DisplayName("同一 run 内 (工具, 规范化参数, dataRevision) 复用结果，但仍绑到新 tool_call_id")
    void sameRunReuseStillPairsEveryToolCallId() {
        AgentTestSupport.WeightTrendTool tool = new AgentTestSupport.WeightTrendTool();
        ToolRegistry registry = AgentTestSupport.registry(tool);
        ToolExecutor executor = AgentTestSupport.executor(registry, limits);
        ToolResultCache cache = new ToolResultCache();

        ExecutedTool first = executor.execute(new ToolCall("id-1", "get_weight_trend", "{\"days\":7}"),
                context, cache);
        // 键序不同、语义相同：规范化后同一个键
        ExecutedTool second = executor.execute(new ToolCall("id-2", "get_weight_trend", "{\"days\": 7 }"),
                context, cache);

        assertThat(tool.calls()).isEqualTo(1);
        assertThat(first.fromCache()).isFalse();
        assertThat(second.fromCache()).isTrue();
        assertThat(second.toolCallId()).isEqualTo("id-2");
        assertThat(second.modelPayload()).isEqualTo(first.modelPayload());
        assertThat(second.requestHits()).isEqualTo(2);
    }

    @Test
    @DisplayName("规范化参数用于缓存键：空白与键序差异不算两次不同的读取")
    void canonicalizationIsOrderIndependent() {
        assertThat(ToolExecutor.canonicalize("{\"b\":2,\"a\":1}"))
                .isEqualTo(ToolExecutor.canonicalize("{\"a\":1,   \"b\":2}"));
        assertThat(ToolExecutor.canonicalize(null)).isEqualTo("{}");
    }

    @Test
    @DisplayName("工具内部异常转成 INTERNAL_ERROR，异常原文不外泄给模型")
    void toolCrashBecomesStructuredError() {
        ExecutedTool outcome = runAllowing(new ToolCall("c1", "get_boom", "{}"), new CrashingTool(), "get_boom");

        assertThat(outcome.errorCode()).isEqualTo(ToolErrorCode.INTERNAL_ERROR);
        assertThat(outcome.modelPayload()).doesNotContain("SQL");
    }

    record BoomArgs() {
    }

    static final class CrashingTool implements AgentTool<BoomArgs, String> {

        @Override
        public ToolDefinition definition() {
            return new ToolDefinition("get_boom", "会抛异常的工具", "{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public Class<BoomArgs> argumentType() {
            return BoomArgs.class;
        }

        @Override
        public ToolResult<String> execute(BoomArgs args, AgentContext ctx) {
            throw new IllegalStateException("内部 SQL 失败 SELECT * FROM user_profile WHERE user_id=42");
        }
    }

    @Test
    @DisplayName("工具名必须匹配 ^[a-zA-Z0-9_-]{1,128}$（供应商侧同一约束）")
    void toolNamePatternIsEnforced() {
        assertThatThrownBy(() -> new ToolDefinition("get profile", "空格不合法", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("get.profile", "点号不合法", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("x".repeat(129), "超长", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
