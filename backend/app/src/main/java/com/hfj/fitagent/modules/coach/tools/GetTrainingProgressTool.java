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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code get_training_progress}：某一个业务日期<b>已确认结束</b>的训练与当天计划的对比。
 *
 * <p>为什么按"计划版本"分组返回，而不是给一个当天总百分比：不同版本的组数不能共用一个分母
 * （stage2-design §6）。把两个版本折成一个数字，就是在制造一个从没发生过的口径。
 *
 * <p>数字一律来自 {@link TrainingMetricsService}，本工具不重算、不改写；
 * 没有已确认记录时相关值为 null 并列入 missingFields，模型据此说"没有记录"，而不是 0%。
 */
@Component
public class GetTrainingProgressTool implements AgentTool<GetTrainingProgressTool.Args, GetTrainingProgressTool.Facts> {

    private final TrainingMetricsService metricsService;

    public GetTrainingProgressTool(TrainingMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** ISO 业务日期；不收 userId（身份只来自认证上下文）。 */
    public record Args(
            @NotNull(message = "business_date 不能为空")
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "必须是 YYYY-MM-DD")
            String businessDate
    ) {
    }

    public record ItemFact(String planItemId,
                           String exerciseCode,
                           String exerciseName,
                           Integer plannedSets,
                           Integer confirmedSets,
                           Integer cappedSets,
                           Integer extraSets,
                           Integer plannedDurationSeconds,
                           Integer recordedDurationSeconds,
                           String durationUnavailableReason) {
    }

    /** 一个计划版本在这一天的对比结果；版本之间不合并。 */
    public record VersionFact(String planId,
                              String planDayId,
                              String planTitle,
                              Integer planRevisionNo,
                              String planStatus,
                              Boolean scheduledForThisDate,
                              BigDecimal setCompletionPct,
                              Integer plannedTargetSets,
                              Integer confirmedSetsCapped,
                              Integer extraSets,
                              Integer recordedMovementSeconds,
                              List<ItemFact> items,
                              List<String> sourceSessionIds,
                              List<String> missingFields) {
    }

    public record Facts(String businessDate,
                        Integer isoWeekday,
                        String ruleVersion,
                        Integer completedSessionCount,
                        Integer recordedEntryCount,
                        Integer confirmedSetCount,
                        Integer sessionElapsedSecondsTotal,
                        Integer recordedMovementSecondsTotal,
                        Integer selfInitiatedEntryCount,
                        List<String> sourceSessionIds,
                        List<VersionFact> planVersions,
                        List<String> missingFields,
                        Long dataRevision) {
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CoachToolNames.GET_TRAINING_PROGRESS,
                "读取指定业务日期内用户已确认结束的训练记录，并与当天计划按版本分组对比。"
                        + "返回封顶后的组数完成度、额外组数、已记录动作时长与场次历时，全部带来源 session。"
                        + "没有已确认记录时相关值为 null 并列入 missingFields：应如实说明没有记录，"
                        + "不得说成 0% 或 100%，也不得把计划安排当成已完成。"
                        + "不同计划版本的数字不得相加或平均。",
                CoachToolSchemas.object(List.of("business_date"),
                        List.<String[]>of(new String[]{"business_date",
                                CoachToolSchemas.isoDate("要查询的业务日期（用户时区口径，YYYY-MM-DD）")})));
    }

    @Override
    public Class<Args> argumentType() {
        return Args.class;
    }

    @Override
    public ToolResult<Facts> execute(Args args, AgentContext ctx) {
        LocalDate date = parse(args.businessDate());
        if (date == null) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED, "business_date 必须是 YYYY-MM-DD");
        }
        MetricsVos.DayComparison day = metricsService.day(ctx.userId(), date);
        MetricsVos.DayRecordSummary summary = day.recordSummary();
        List<String> sessionIds = summary == null || summary.sessionIds() == null
                ? List.of() : summary.sessionIds();

        List<VersionFact> versions = new ArrayList<>(day.planVersions().size());
        Integer revision = null;
        for (MetricsVos.PlanVersionComparison v : day.planVersions()) {
            List<ItemFact> items = new ArrayList<>();
            for (MetricsVos.ItemComparison item : v.items()) {
                MetricsVos.PlannedSide planned = item.planned();
                MetricsVos.ActualSide actual = item.actual();
                items.add(new ItemFact(item.planItemId(), item.exerciseCode(), item.exerciseName(),
                        planned == null ? null : planned.targetSets(),
                        actual == null ? null : actual.confirmedSets(),
                        actual == null ? null : actual.cappedSets(),
                        actual == null ? null : actual.extraSets(),
                        planned == null ? null : planned.targetDurationSeconds(),
                        actual == null ? null : actual.recordedDurationSeconds(),
                        actual == null ? null : actual.durationUnavailableReason()));
            }
            versions.add(new VersionFact(v.planId(), v.planDayId(), v.planTitle(), v.planRevisionNo(),
                    v.planStatus(), v.scheduledForThisDate(),
                    decimal(v.metrics(), MetricsRules.SET_COMPLETION_PCT),
                    integer(v.metrics(), MetricsRules.PLANNED_TARGET_SETS),
                    integer(v.metrics(), MetricsRules.CONFIRMED_SETS_CAPPED),
                    integer(v.metrics(), MetricsRules.EXTRA_SETS),
                    integer(v.metrics(), MetricsRules.RECORDED_MOVEMENT_SECONDS),
                    List.copyOf(items),
                    v.sourceSessionIds() == null ? List.of() : List.copyOf(v.sourceSessionIds()),
                    v.missingFields() == null ? List.of() : List.copyOf(v.missingFields())));
            revision = v.planRevisionNo();
        }

        List<Evidence> evidence = new ArrayList<>(sessionIds.size());
        for (String sessionId : sessionIds) {
            evidence.add(new Evidence(CoachToolNames.TABLE_WORKOUT_SESSION, sessionId, revision, date,
                    MetricsRules.RULE_VERSION, ctx.dataRevision()));
        }

        List<String> missing = new ArrayList<>();
        if (day.missingFields() != null) {
            missing.addAll(day.missingFields());
        }
        if (sessionIds.isEmpty()) {
            missing.add(MetricsRules.NO_CONFIRMED_SESSION);
        }
        MetricsVos.SelfInitiatedSummary self = day.selfInitiated();

        Facts facts = new Facts(date.toString(), day.isoWeekday(), MetricsRules.RULE_VERSION,
                summary == null ? 0 : summary.completedSessionCount(),
                summary == null ? null : summary.recordedEntryCount(),
                summary == null ? null : summary.confirmedSetCount(),
                summary == null ? null : summary.sessionElapsedSecondsTotal(),
                summary == null ? null : summary.recordedMovementSecondsTotal(),
                self == null || self.entries() == null ? 0 : self.entries().size(),
                List.copyOf(sessionIds), List.copyOf(versions), List.copyOf(missing), ctx.dataRevision());

        if (missing.isEmpty() && !evidence.isEmpty()) {
            return ToolResult.ok(facts, List.copyOf(evidence), ctx.dataRevision());
        }
        return ToolResult.partial(facts, List.copyOf(evidence), List.copyOf(missing), ctx.dataRevision(),
                "当天没有可引用的已确认训练记录或指标不完整：请按 missingFields 如实说明，不要用 0 或计划值补");
    }

    private static BigDecimal decimal(List<MetricsVos.Metric> metrics, String code) {
        MetricsVos.Metric found = find(metrics, code);
        return found == null ? null : found.value();
    }

    private static Integer integer(List<MetricsVos.Metric> metrics, String code) {
        BigDecimal value = decimal(metrics, code);
        return value == null ? null : value.intValue();
    }

    private static MetricsVos.Metric find(List<MetricsVos.Metric> metrics, String code) {
        if (metrics == null) {
            return null;
        }
        return metrics.stream().filter(x -> code.equals(x.code())).findFirst().orElse(null);
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
