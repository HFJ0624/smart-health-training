package com.hfj.fitagent.modules.coach.tools;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.tool.AgentTool;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolResult;
import com.hfj.fitagent.modules.training.TrainingPlanService;
import com.hfj.fitagent.modules.training.vo.PlanDayVo;
import com.hfj.fitagent.modules.training.vo.PlanItemVo;
import com.hfj.fitagent.modules.training.vo.TodayPlanVo;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code get_training_plan}：当前生效计划在这一天安排什么（baseline §3.3 确认可注册——
 * {@code TrainingPlanService.findToday} 已有且四种真实形态都有测试）。
 *
 * <p><b>只做透传，不做第二份真值</b>：日期由 training 模块按用户时区在服务端推导，
 * 本工具要求 {@code business_date} 必须等于 {@link AgentContext#businessDate()}，否则给结构化错误。
 * 在 coach 里另写一套"按任意日期查计划日"就成了第二个实现（AGENTS.md：业务日期由服务端推导，客户端不传）。
 *
 * <p>四种形态必须可区分，否则模型会把"没计划"说成"今天休息"：
 * <ol>
 *   <li>{@code NO_ACTIVE_PLAN}：没有 ACTIVE 计划；</li>
 *   <li>{@code NOTHING_SCHEDULED}：ACTIVE 计划存在，但这一天没有计划日（或 {@code endsOn} 已过）；</li>
 *   <li>{@code REST_DAY}：计划日标记休息；</li>
 *   <li>{@code TRAINING_DAY}：训练日并给出条目。</li>
 * </ol>
 *
 * <p>{@code plannedSets} 只是<b>计划侧</b>组数求和。这里没有任何完成度：
 * 执行记录（{@code workout_*}）不存在，完成率有分母没分子（baseline §3.3 已点破），
 * 所以模型也没有任何路径从这里读出一个百分比。
 */
@Component
public class GetTrainingPlanTool implements AgentTool<GetTrainingPlanTool.Args, GetTrainingPlanTool.Facts> {

    public static final String SHAPE_NO_ACTIVE_PLAN = "NO_ACTIVE_PLAN";
    public static final String SHAPE_NOTHING_SCHEDULED = "NOTHING_SCHEDULED";
    public static final String SHAPE_REST_DAY = "REST_DAY";
    public static final String SHAPE_TRAINING_DAY = "TRAINING_DAY";

    private final TrainingPlanService planService;

    public GetTrainingPlanTool(TrainingPlanService planService) {
        this.planService = planService;
    }

    /** @param businessDate ISO 日期；必须等于服务端推导的今天。 */
    public record Args(
            @NotNull(message = "business_date 不能为空")
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "必须是 YYYY-MM-DD")
            String businessDate
    ) {
    }

    /**
     * @param items 休息日与未排程时恒为<b>空数组</b>（不是 null）：与 {@code PlanDayVo.items} 口径一致，
     *              模型不必区分"没有"与"没读到"
     * @param planRevision 计划版本号，同时进证据坐标（§9：改版后旧引用不得误命中）
     */
    public record Facts(String shape,
                        String businessDate,
                        Integer isoWeekday,
                        String planId,
                        String planGroupUuid,
                        Integer planRevision,
                        String dayId,
                        String dayTitle,
                        Boolean restDay,
                        String dayNotes,
                        List<Item> items,
                        Integer plannedSets,
                        Long dataRevision) {
    }

    /** 条目只带计划值：全部来自 training_plan_item，未设定就是 null（0 与 null 不互换）。 */
    public record Item(String exerciseId,
                       String exerciseCode,
                       String exerciseName,
                       Integer positionNo,
                       Integer targetSets,
                       Integer repsMin,
                       Integer repsMax,
                       BigDecimal targetExternalLoadKg,
                       Integer targetDurationSeconds,
                       Integer restSeconds) {
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CoachToolNames.GET_TRAINING_PLAN,
                "查询当前生效训练计划在指定业务日期的安排。business_date 必须是服务端推导的今天（YYYY-MM-DD）。"
                        + "shape 取值：NO_ACTIVE_PLAN（没有生效计划）/ NOTHING_SCHEDULED（有计划但今天没排条目）"
                        + "/ REST_DAY（休息日）/ TRAINING_DAY（训练日含条目）。"
                        + "只有计划值，没有任何执行记录或完成度，不要推算完成百分比。",
                CoachToolSchemas.object(List.of("business_date"),
                        List.<String[]>of(new String[]{"business_date",
                                CoachToolSchemas.isoDate("服务端推导的当前业务日期，必须与系统提示中的今天一致")})));
    }

    @Override
    public Class<Args> argumentType() {
        return Args.class;
    }

    @Override
    public ToolResult<Facts> execute(Args args, AgentContext ctx) {
        LocalDate requested = parse(args.businessDate());
        if (requested == null) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED,
                    "business_date 必须是 YYYY-MM-DD 的 ISO 日期");
        }
        if (!requested.equals(ctx.businessDate())) {
            return ToolResult.error(ToolErrorCode.VALIDATION_FAILED,
                    "只支持查询服务端推导的当前业务日期 " + ctx.businessDate() + "；其它日期没有可信来源");
        }

        TodayPlanVo today = planService.findToday(ctx.userId(), ctx.zone());
        if (today == null) {
            return ToolResult.error(ToolErrorCode.RESOURCE_NOT_FOUND, "未取到当天的计划读取结果");
        }
        LocalDate businessDate = today.businessDate() == null ? ctx.businessDate() : today.businessDate();
        String businessDateText = businessDate.toString();
        List<String> missing = new ArrayList<>();

        if (today.activePlanId() == null) {
            missing.add("activeTrainingPlan");
            Facts facts = new Facts(SHAPE_NO_ACTIVE_PLAN, businessDateText, today.isoWeekday(),
                    null, null, null, null, null, null, null, List.of(), 0, ctx.dataRevision());
            return ToolResult.ok(facts, List.of(), List.copyOf(missing), ctx.dataRevision());
        }

        List<Evidence> evidence = new ArrayList<>(2);
        evidence.add(new Evidence(CoachToolNames.TABLE_TRAINING_PLAN, today.activePlanId(),
                today.revisionNo(), businessDate, CoachToolNames.RULE_NONE, ctx.dataRevision()));

        PlanDayVo day = today.day();
        if (day == null) {
            missing.add("plannedDayForDate");
            Facts facts = new Facts(SHAPE_NOTHING_SCHEDULED, businessDateText, today.isoWeekday(),
                    today.activePlanId(), today.planGroupUuid(), today.revisionNo(), null, null, null, null,
                    List.of(), 0, ctx.dataRevision());
            return ToolResult.ok(facts, List.copyOf(evidence), List.copyOf(missing), ctx.dataRevision());
        }

        evidence.add(new Evidence(CoachToolNames.TABLE_TRAINING_PLAN_DAY, day.id(), today.revisionNo(),
                businessDate, CoachToolNames.RULE_NONE, ctx.dataRevision()));
        List<Item> items = day.items() == null ? List.of() : day.items().stream()
                .map(GetTrainingPlanTool::toItem).toList();
        boolean rest = Boolean.TRUE.equals(day.isRestDay());
        // items 已由 PlanItemVo 转成本工具自己的 Item 视图，这里引用 Item 的访问器
        int plannedSets = items.stream().map(Item::targetSets).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
        String shape = rest ? SHAPE_REST_DAY : (items.isEmpty() ? SHAPE_NOTHING_SCHEDULED : SHAPE_TRAINING_DAY);
        if (!rest && items.isEmpty()) {
            // 非休息日却零条目：这是"没排内容"而不是"休息"，如实点名让模型说对的那句
            missing.add("plannedItems");
        }
        Facts facts = new Facts(shape, businessDateText, today.isoWeekday(), today.activePlanId(),
                today.planGroupUuid(), today.revisionNo(), day.id(), day.title(), rest, day.notes(), items,
                plannedSets, ctx.dataRevision());
        return ToolResult.ok(facts, List.copyOf(evidence), List.copyOf(missing), ctx.dataRevision());
    }

    private static Item toItem(PlanItemVo item) {
        // PlanItemVo 是 record：访问器是 exerciseId() 而不是 getExerciseId()
        return new Item(item.exerciseId(), item.exerciseCode(), item.exerciseName(),
                item.positionNo(), item.targetSets(), item.repsMin(), item.repsMax(),
                item.targetExternalLoadKg(), item.targetDurationSeconds(), item.restSeconds());
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
