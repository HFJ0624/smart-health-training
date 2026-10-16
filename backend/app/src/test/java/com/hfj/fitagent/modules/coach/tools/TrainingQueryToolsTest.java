package com.hfj.fitagent.modules.coach.tools;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.answer.AgentAnswer;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.RunCancellation;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.core.StopReason;
import com.hfj.fitagent.agent.fake.FakeModelClient;
import com.hfj.fitagent.agent.model.FinishReason;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.ModelResponse;
import com.hfj.fitagent.agent.model.ModelUsage;
import com.hfj.fitagent.agent.model.ToolCall;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolResult;
import com.hfj.fitagent.agent.trace.EvidenceRegistry;
import com.hfj.fitagent.agent.trace.TraceRecorder;
import com.hfj.fitagent.modules.metrics.MetricsRules;
import com.hfj.fitagent.modules.metrics.TrainingMetricsService;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 两个训练查询工具的契约（阶段 2 验收项 10 的单元层部分）。
 *
 * <p>要钉住的是三件事：数字<b>原样透传</b>自 metrics 服务（模型侧看不到可以被"顺手改一下"的中间值）、
 * 每条事实都带 session 来源与规则版本、以及周工具<b>结构上没有百分比字段</b>——
 * 最后一条用反射断言，因为一旦有人加一个 weeklyCompletionPct，全项目单测都不会红，
 * 只有这条会。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrainingQueryToolsTest {

    private static final LocalDate FRIDAY = LocalDate.of(2026, 10, 16);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    @Mock private TrainingMetricsService metricsService;

    private static AgentContext ctx() {
        return AgentTestSupport.context(RunLimits.defaults());
    }

    // ---------------------------------------------------------------- get_training_progress

    @Test
    @DisplayName("日期格式非法：VALIDATION_FAILED，不落到服务层")
    void progressRejectsBadDate() {
        GetTrainingProgressTool tool = new GetTrainingProgressTool(metricsService);

        assertThat(tool.execute(new GetTrainingProgressTool.Args(""), ctx()).code())
                .isEqualTo(ToolErrorCode.VALIDATION_FAILED);
        assertThat(tool.execute(new GetTrainingProgressTool.Args("16/10/2026"), ctx()).code())
                .isEqualTo(ToolErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("封顶后的 50% 与额外 2 组原样透传，并绑定到真实 session 与规则版本")
    void progressPassesThroughCappedNumbers() {
        when(metricsService.day(eq(AgentTestSupport.USER_ID), any(LocalDate.class)))
                .thenReturn(dayWith("50.00", 6, 3, 2, List.of("3001")));

        GetTrainingProgressTool tool = new GetTrainingProgressTool(metricsService);
        ToolResult<GetTrainingProgressTool.Facts> result =
                tool.execute(new GetTrainingProgressTool.Args(FRIDAY.toString()), ctx());

        GetTrainingProgressTool.Facts facts = result.data();
        assertThat(facts.businessDate()).isEqualTo(FRIDAY.toString());
        assertThat(facts.ruleVersion()).isEqualTo(MetricsRules.RULE_VERSION);
        assertThat(facts.planVersions()).hasSize(1);
        GetTrainingProgressTool.VersionFact version = facts.planVersions().get(0);
        assertThat(version.setCompletionPct()).isEqualByComparingTo("50.00");
        assertThat(version.plannedTargetSets()).isEqualTo(6);
        assertThat(version.confirmedSetsCapped()).isEqualTo(3);
        assertThat(version.extraSets()).isEqualTo(2);
        assertThat(version.planRevisionNo()).isEqualTo(4);

        assertThat(result.evidenceRefs()).hasSize(1);
        Evidence evidence = result.evidenceRefs().get(0);
        assertThat(evidence.sourceTable()).isEqualTo(CoachToolNames.TABLE_WORKOUT_SESSION);
        assertThat(evidence.sourceId()).isEqualTo("3001");
        assertThat(evidence.ruleVersion()).isEqualTo(MetricsRules.RULE_VERSION);
        assertThat(evidence.businessDate()).isEqualTo(FRIDAY);
        assertThat(evidence.dataRevision()).isEqualTo(AgentTestSupport.DATA_REVISION);
    }

    @Test
    @DisplayName("当天没有已确认记录：partial + NO_CONFIRMED_SESSION，不返回 0%")
    void progressReportsMissingInsteadOfZero() {
        when(metricsService.day(any(Long.class), any(LocalDate.class))).thenReturn(dayWith(null, 0, 0, 0, List.of()));

        GetTrainingProgressTool tool = new GetTrainingProgressTool(metricsService);
        ToolResult<GetTrainingProgressTool.Facts> result =
                tool.execute(new GetTrainingProgressTool.Args(FRIDAY.toString()), ctx());

        assertThat(result.data().planVersions().get(0).setCompletionPct()).isNull();
        assertThat(result.missingFields()).contains(MetricsRules.NO_CONFIRMED_SESSION);
        assertThat(result.evidenceRefs()).isEmpty();
    }

    // ---------------------------------------------------------------- get_weekly_training_summary

    @Test
    @DisplayName("起始日不是周一：直接拒绝，避免最近一周这种口径随查询当天漂移")
    void weekRequiresMonday() {
        GetWeeklyTrainingSummaryTool tool = new GetWeeklyTrainingSummaryTool(metricsService);

        ToolResult<?> result = tool.execute(new GetWeeklyTrainingSummaryTool.Args(FRIDAY.toString()), ctx());

        assertThat(result.code()).isEqualTo(ToolErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("周统计只给场次/天数/组数/时长；结构上不存在任何百分比字段")
    void weekHasNoPercentageField() {
        MetricsVos.WeekSummary week = new MetricsVos.WeekSummary(MONDAY, MONDAY.plusDays(6),
                MetricsRules.RULE_VERSION, "17",
                List.of(metric(MetricsRules.COMPLETED_SESSION_COUNT, "3"),
                        metric(MetricsRules.DISTINCT_TRAINING_DAY_COUNT, "2"),
                        metric(MetricsRules.CONFIRMED_SETS_TOTAL, "18"),
                        metric(MetricsRules.SESSION_ELAPSED_SECONDS, "5400"),
                        metricWithSources(MetricsRules.EVIDENCED_MOVEMENT_SECONDS, "1800", List.of("3001", "3002"))),
                List.of(new MetricsVos.WeekDayRow(MONDAY, 1, 9, 900, null, 1800),
                        new MetricsVos.WeekDayRow(MONDAY.plusDays(1), 2, 9, 900, null, 3600),
                        new MetricsVos.WeekDayRow(MONDAY.plusDays(2), 0, 0, null,
                                MetricsRules.DURATION_NOT_RECORDED, null)),
                List.of());
        when(metricsService.week(any(Long.class), any(LocalDate.class))).thenReturn(week);

        GetWeeklyTrainingSummaryTool tool = new GetWeeklyTrainingSummaryTool(metricsService);
        ToolResult<GetWeeklyTrainingSummaryTool.Facts> result =
                tool.execute(new GetWeeklyTrainingSummaryTool.Args(MONDAY.toString()), ctx());

        GetWeeklyTrainingSummaryTool.Facts facts = result.data();
        assertThat(facts.completedSessionCount()).isEqualTo(3);
        assertThat(facts.distinctTrainingDayCount()).isEqualTo(2);
        assertThat(facts.confirmedSetsTotal()).isEqualTo(18);
        assertThat(facts.evidencedMovementSecondsTotal()).isEqualTo(1800);
        assertThat(facts.days()).hasSize(3);
        assertThat(facts.sourceSessionIds()).containsExactlyInAnyOrder("3001", "3002");
        assertThat(result.evidenceRefs()).hasSize(2);

        List<String> percentFields = Arrays.stream(GetWeeklyTrainingSummaryTool.Facts.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(name -> name.toLowerCase().contains("pct") || name.toLowerCase().contains("percent"))
                .toList();
        assertThat(percentFields).as("周统计出现百分比字段就是允许模型把各天平均").isEmpty();
    }

    // ---------------------------------------------------------------- 注册表

    @Test
    @DisplayName("白名单由 CoachToolNames.ALL 单点定义，且新增的两个工具已在其中")
    void allowedToolsDeriveFromSingleSource() {
        Set<String> all = CoachToolNames.ALL;
        assertThat(all).contains("get_training_progress", "get_weekly_training_summary");
        assertThat(all).hasSize(5);
        assertThat(new CoachToolConfiguration().coachAllowedTools()).isEqualTo(all);
    }

    // ---------------------------------------------------------------- 过执行循环（验收项 10）

    @Test
    @DisplayName("新工具真的经过 AgentRunner 循环：tool_call_id 配对、数字未被模型改写、仍走费用网关")
    void progressToolRunsInsideTheLoop() {
        when(metricsService.day(eq(AgentTestSupport.USER_ID), any(LocalDate.class)))
                .thenReturn(dayWith("50.00", 6, 3, 2, List.of("3001")));

        FakeModelClient client = AgentTestSupport.fake();
        client.enqueue(toolCallResponse("call-p", "get_training_progress",
                "{\"businessDate\":\"" + FRIDAY + "\"}"));
        client.enqueue(answerResponse("今天已记录组数完成度 50.00%（ev-1）。"));

        AgentTestSupport.ScriptedGateway gateway = new AgentTestSupport.ScriptedGateway(client);
        GetTrainingProgressTool tool = new GetTrainingProgressTool(metricsService);
        EvidenceRegistry evidence = new EvidenceRegistry();
        AgentContext context = AgentTestSupport.contextWithTools(CoachToolNames.ALL, RunLimits.defaults());

        AgentAnswer result = AgentTestSupport
                .runner(gateway, AgentTestSupport.registry(tool), RunLimits.defaults())
                .run(AgentTestSupport.request(context, "我今天练得怎么样？",
                        TraceRecorder.inMemory(), evidence, new RunCancellation(), List.of()));

        assertThat(result.stopReason()).isEqualTo(StopReason.SUCCEEDED);
        // 两次调用都必须经网关（费用关口），一次也不能绕过
        assertThat(gateway.requests()).hasSize(2);
        // 服务端统计确实被这次循环读到（而不是模型自己编了一个数）
        verify(metricsService, times(1)).day(eq(AgentTestSupport.USER_ID), eq(FRIDAY));

        List<ModelMessage> second = gateway.requests().get(1).messages();
        List<ModelMessage> assistantTurns = second.stream().filter(ModelMessage::isAssistantToolCallTurn).toList();
        List<ModelMessage> toolMessages = second.stream()
                .filter(m -> m.role() == com.hfj.fitagent.agent.model.MessageRole.TOOL).toList();
        assertThat(assistantTurns).hasSize(1);
        assertThat(assistantTurns.get(0).toolCalls()).extracting(ToolCall::id).containsExactly("call-p");
        assertThat(toolMessages).hasSize(1);
        assertThat(toolMessages.get(0).toolCallId()).isEqualTo("call-p");
        // 回传给模型的文本里，封顶值与口径版本原样出现：模型没有任何"中间四舍五入"可以改写
        assertThat(toolMessages.get(0).content())
                .contains("50.00")
                .contains(MetricsRules.RULE_VERSION)
                .contains("evidenceId");
        assertThat(evidence.size()).isEqualTo(1);
        // 引用句柄确实交给了模型：本 run 唯一那条证据的 ID 出现在回传文本里。
        // 「模型引用后生成事实卡、且卡片取工具值」这一段由 AnswerAssemblerTest 钉住，此处不重复造轮子。
        assertThat(evidence.ids()).hasSize(1);
        assertThat(toolMessages.get(0).content()).contains(evidence.ids().get(0));
    }

    // ---------------------------------------------------------------- 夹具

    private static ModelResponse answerResponse(String text) {
        return new ModelResponse(text, List.of(), FinishReason.STOP,
                ModelUsage.of(10L, 90L, 20L, null), "req-answer", false);
    }

    private static ModelResponse toolCallResponse(String id, String name, String arguments) {
        return new ModelResponse("", List.of(new ToolCall(id, name, arguments)), FinishReason.TOOL_CALLS,
                ModelUsage.of(10L, 90L, 20L, null), "req-tools", false);
    }

    private static MetricsVos.DayComparison dayWith(String setCompletionPct, int plannedTargetSets,
                                                    int confirmedCapped, int extraSets, List<String> sessionIds) {
        List<MetricsVos.Metric> metrics = List.of(
                metric(MetricsRules.SET_COMPLETION_PCT, setCompletionPct),
                metric(MetricsRules.PLANNED_TARGET_SETS, String.valueOf(plannedTargetSets)),
                metric(MetricsRules.CONFIRMED_SETS_CAPPED, String.valueOf(confirmedCapped)),
                metric(MetricsRules.EXTRA_SETS, String.valueOf(extraSets)));
        MetricsVos.PlanVersionComparison version = new MetricsVos.PlanVersionComparison("30", "40", "拉",
                4, "ACTIVE", Boolean.TRUE,
                List.of(new MetricsVos.ItemComparison("88", "9", "SQUAT", "深蹲", 1,
                        new MetricsVos.PlannedSide(3, 8, 10, null, null, null),
                        new MetricsVos.ActualSide(confirmedCapped, confirmedCapped, extraSets, null, null, null),
                        new MetricsVos.RepsCheck(Boolean.TRUE, confirmedCapped, confirmedCapped, 0, 0, 0, null, null),
                        List.of(), sessionIds, List.of(), List.of())),
                metrics, sessionIds, List.of());
        MetricsVos.DayRecordSummary summary = sessionIds.isEmpty() ? null
                : new MetricsVos.DayRecordSummary(sessionIds.size(), sessionIds, 1, confirmedCapped,
                1800, 900, null, null, List.of(), List.of());
        return new MetricsVos.DayComparison(FRIDAY, 5, MetricsRules.RULE_VERSION, "17",
                List.of(version),
                new MetricsVos.SelfInitiatedSummary(List.of(), List.of(), List.of()),
                summary, List.of());
    }

    private static MetricsVos.Metric metric(String code, String value) {
        return metricWithSources(code, value, List.of());
    }

    private static MetricsVos.Metric metricWithSources(String code, String value, List<String> sessionIds) {
        BigDecimal decimal = value == null ? null : new BigDecimal(value);
        return new MetricsVos.Metric(code, decimal, MetricsRules.UNIT_SETS, MetricsRules.RULE_VERSION,
                "30", 4, sessionIds, List.of(), List.of(),
                decimal == null ? MetricsRules.DURATION_NOT_RECORDED : null, "17");
    }
}
