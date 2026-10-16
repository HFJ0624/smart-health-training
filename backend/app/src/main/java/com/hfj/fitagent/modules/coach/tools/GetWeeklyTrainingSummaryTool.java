package com.hfj.fitagent.modules.coach.tools;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.tool.AgentTool;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolResult;
import com.hfj.fitagent.modules.metrics.MetricsRules;
import com.hfj.fitagent.modules.metrics.TrainingMetricsService;
import com.hfj.fitagent.modules.metrics.vo.MetricsVos;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code get_weekly_training_summary}：一个自然周（周一至周日）的基础统计。
 *
 * <p>为什么单独有周工具，而不是让模型连发 7 次单日查询：那样既烧预算，又会诱导模型把 7 个
 * 百分比<b>平均</b>成一个"周完成度"——本工具因此<b>只给场次数、训练天数、组数与时长</b>，
 * 不提供也不允许任何形式的周百分比（stage2-design §6）。
 *
 * <p>起始日强制周一：口径含糊的"最近一周"会随查询当天漂移，两次答案对不上却都自称正确。
 */
@Component
public class GetWeeklyTrainingSummaryTool
        implements AgentTool<GetWeeklyTrainingSummaryTool.Args, GetWeeklyTrainingSummaryTool.Facts> {

    private final TrainingMetricsService metricsService;

    public GetWeeklyTrainingSummaryTool(TrainingMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** 必须是周一（ISO YYYY-MM-DD）。 */
    public record Args(
            @NotNull(message = "week_start_date 不能为空")
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "必须是 YYYY-MM-DD")
            String weekStartDate
    ) {
    }

    public record DayRow(String businessDate,
                         Integer sessionCount,
                         Integer confirmedSetCount,
                         Integer recordedMovementSeconds,
                         String movementUnavailableReason,
                         Integer sessionElapsedSeconds) {
    }

    public record Facts(String weekStartDate,
                        String weekEndDate,
                        String ruleVersion,
                        Integer completedSessionCount,
                        Integer distinctTrainingDayCount,
                        Integer confirmedSetsTotal,
                        Integer sessionElapsedSecondsTotal,
                        Integer evidencedMovementSecondsTotal,
                        String movementUnavailableReason,
                        List<DayRow> days,
                        List<String> sourceSessionIds,
                        List<String> missingFields,
                        Long dataRevision) {
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CoachToolNames.GET_WEEKLY_TRAINING_SUMMARY,
                "读取一个自然周（周一至周日）内用户已确认结束的训练基础统计：场次数、不同训练日期数、"
                        + "已确认组数、场次历时与有依据的动作时长。本工具不提供周完成度百分比，"
                        + "也不得把各天百分比平均成一个数；缺失项在 missingFields 与 movementUnavailableReason 里写明。",
                CoachToolSchemas.object(List.of("week_start_date"),
                        List.<String[]>of(new String[]{"week_start_date",
                                CoachToolSchemas.isoDate("周起始日，必须是周一；不是周一会返回校验失败")})));
    }

    @Override
    public Class<Args> argumentType() {
        return Args.class;
    }

    @Override
    public ToolResult<Facts> execute(Args args, AgentContext ctx) {
        LocalDate start = parse(args.weekStartDate());
        if (start == null) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED, "week_start_date 必须是 YYYY-MM-DD");
        }
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED,
                    "week_start_date 必须是周一（" + start + " 是" + start.getDayOfWeek() + "）");
        }
        MetricsVos.WeekSummary week = metricsService.week(ctx.userId(), start);
        List<MetricsVos.Metric> metrics = week.metrics() == null ? List.of() : week.metrics();

        List<DayRow> rows = new ArrayList<>();
        Set<String> sessionIds = new LinkedHashSet<>();
        for (MetricsVos.WeekDayRow row : week.days()) {
            rows.add(new DayRow(row.businessDate() == null ? null : row.businessDate().toString(),
                    row.sessionCount(), row.confirmedSetCount(), row.recordedMovementSeconds(),
                    row.movementUnavailableReason(), row.sessionElapsedSeconds()));
        }
        for (MetricsVos.Metric metric : metrics) {
            if (metric.sourceSessionIds() != null) {
                sessionIds.addAll(metric.sourceSessionIds());
            }
        }

        List<Evidence> evidence = new ArrayList<>(sessionIds.size());
        for (String sessionId : sessionIds) {
            evidence.add(new Evidence(CoachToolNames.TABLE_WORKOUT_SESSION, sessionId, null,
                    start, MetricsRules.RULE_VERSION, ctx.dataRevision()));
        }

        List<String> missing = new ArrayList<>();
        if (week.missingFields() != null) {
            missing.addAll(week.missingFields());
        }
        if (sessionIds.isEmpty()) {
            missing.add("noConfirmedSessionInWeek");
        }

        Facts facts = new Facts(week.weekStartDate().toString(), week.weekEndDate().toString(),
                MetricsRules.RULE_VERSION,
                integer(metrics, MetricsRules.COMPLETED_SESSION_COUNT),
                integer(metrics, MetricsRules.DISTINCT_TRAINING_DAY_COUNT),
                integer(metrics, MetricsRules.CONFIRMED_SETS_TOTAL),
                integer(metrics, MetricsRules.SESSION_ELAPSED_SECONDS),
                integer(metrics, MetricsRules.EVIDENCED_MOVEMENT_SECONDS),
                string(metrics, MetricsRules.EVIDENCED_MOVEMENT_SECONDS),
                List.copyOf(rows), List.copyOf(sessionIds), List.copyOf(missing), ctx.dataRevision());

        if (missing.isEmpty() && !evidence.isEmpty()) {
            return ToolResult.ok(facts, List.copyOf(evidence), ctx.dataRevision());
        }
        return ToolResult.partial(facts, List.copyOf(evidence), List.copyOf(missing), ctx.dataRevision(),
                "本周没有已确认训练记录或时长依据不完整：请按缺失项如实说明，不要补 0 或推算");
    }

    private static MetricsVos.Metric find(List<MetricsVos.Metric> metrics, String code) {
        return metrics.stream().filter(x -> code.equals(x.code())).findFirst().orElse(null);
    }

    private static Integer integer(List<MetricsVos.Metric> metrics, String code) {
        MetricsVos.Metric found = find(metrics, code);
        return found == null || found.value() == null ? null : found.value().intValue();
    }

    /** 指标不可用时的原因文案（value 为 null 才有意义，否则返回 null）。 */
    private static String string(List<MetricsVos.Metric> metrics, String code) {
        MetricsVos.Metric found = find(metrics, code);
        if (found == null || found.value() != null) {
            return null;
        }
        return found.unavailableReason();
    }

    private static LocalDate parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
