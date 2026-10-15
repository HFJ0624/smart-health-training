package com.hfj.fitagent.modules.coach.tools;

import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.tool.AgentTool;
import com.hfj.fitagent.agent.tool.Evidence;
import com.hfj.fitagent.agent.tool.ToolDefinition;
import com.hfj.fitagent.agent.tool.ToolErrorCode;
import com.hfj.fitagent.agent.tool.ToolResult;
import com.hfj.fitagent.modules.profile.service.UserGoalService;
import com.hfj.fitagent.modules.profile.service.UserProfileService;
import com.hfj.fitagent.modules.profile.vo.GoalVo;
import com.hfj.fitagent.modules.profile.vo.ProfileVo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code get_profile}：档案 + 当前 ACTIVE 目标（baseline §3.3 确认可注册——两个服务都已存在且有测试）。
 *
 * <p><b>无参数</b>：身份来自 {@link AgentContext}（服务端装配），参数类型里没有任何字段，
 * 所以"前端/模型传 userId 决定读谁的数据"在本工具不可表示。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>不外泄身份材料</b>：返回体里没有 openid / unionId / appId，也没有任何凭据字段——
 *       读的是 {@code ProfileVo}/{@code GoalVo} 两个出参，它们的类型本身就没有这些列（{@code user_account}
 *       的微信三列不在档案服务里）。</li>
 *   <li><b>缺失显式列出</b>（design §9）：没有档案就列 {@code missingFields}，
 *       不以 0 或"成人平均身高"之类的默认值填补，也不引入任何未经说明的医学阈值（AGENTS.md）。</li>
 *   <li><b>数值只来自服务</b>：{@code BigDecimal} 原样带出，不在这个适配层做任何换算或取整。</li>
 * </ul>
 */
@Component
public class GetProfileTool implements AgentTool<GetProfileTool.Args, GetProfileTool.Facts> {

    private final UserProfileService profileService;
    private final UserGoalService goalService;

    public GetProfileTool(UserProfileService profileService, UserGoalService goalService) {
        this.profileService = profileService;
        this.goalService = goalService;
    }

    /** 无参数：空 record，schema 里 properties 为空且拒绝任何额外字段。 */
    public record Args() {
    }

    /**
     * @param heightCm 档案身高；档案缺失或未填时为 null（<b>不</b>补默认值）
     * @param goalStatus 无 ACTIVE 目标时为 null，并在 missingFields 里点名
     */
    public record Facts(String profileId,
                        String goalId,
                        BigDecimal heightCm,
                        String trainingLevel,
                        List<String> equipment,
                        List<String> trainingConstraints,
                        String goalType,
                        BigDecimal targetWeightKg,
                        Integer weeklySessionsTarget,
                        String goalStatus,
                        Long dataRevision) {
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(CoachToolNames.GET_PROFILE,
                "读取本人身体档案与当前生效目标：身高、训练经验等级、可用器械、训练限制备注、"
                        + "目标类型、目标体重、每周目标训练次数。任何字段缺失都会在 missingFields 里明确列出，"
                        + "不要把缺失当成 0 或默认值。没有参数。",
                CoachToolSchemas.empty());
    }

    @Override
    public Class<Args> argumentType() {
        return Args.class;
    }

    @Override
    public ToolResult<Facts> execute(Args args, AgentContext ctx) {
        long userId = ctx.userId();
        ProfileVo profile = profileService.find(userId);
        GoalVo goal = goalService.findActive(userId);
        if (profile == null && goal == null) {
            // "还没建立档案"是真实的空状态，不是后端坏了：给结构化结果而不是 INTERNAL_ERROR
            return ToolResult.error(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "尚未建立身体档案，也没有生效中的目标；请如实告知用户需要先建立档案，不要使用任何默认值");
        }

        List<String> missing = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        if (profile != null) {
            evidence.add(new Evidence(CoachToolNames.TABLE_USER_PROFILE, profile.id(), null,
                    ctx.businessDate(), CoachToolNames.RULE_NONE, ctx.dataRevision()));
            if (profile.heightCm() == null) {
                missing.add("heightCm");
            }
            if (isBlank(profile.trainingLevel())) {
                missing.add("trainingLevel");
            }
            if (profile.equipment() == null || profile.equipment().isEmpty()) {
                missing.add("equipment");
            }
        } else {
            missing.add("profile");
        }
        if (goal != null) {
            evidence.add(new Evidence(CoachToolNames.TABLE_USER_GOAL, goal.id(), null,
                    ctx.businessDate(), CoachToolNames.RULE_NONE, ctx.dataRevision()));
            if (isBlank(goal.goalType())) {
                missing.add("goalType");
            }
            if (goal.targetWeightKg() == null) {
                missing.add("targetWeightKg");
            }
            if (goal.weeklySessionsTarget() == null) {
                missing.add("weeklySessionsTarget");
            }
        } else {
            missing.add("activeGoal");
        }

        Facts facts = new Facts(
                profile == null ? null : profile.id(),
                goal == null ? null : goal.id(),
                profile == null ? null : profile.heightCm(),
                profile == null ? null : profile.trainingLevel(),
                profile == null ? List.of() : safeList(profile.equipment()),
                profile == null ? List.of() : safeList(profile.trainingConstraints()),
                goal == null ? null : goal.goalType(),
                goal == null ? null : goal.targetWeightKg(),
                goal == null ? null : goal.weeklySessionsTarget(),
                goal == null ? null : goal.status(),
                ctx.dataRevision());
        // missingFields 非空 → ToolResult.ok 把 dataComplete 置 false：结论口径按不完整处理
        return ToolResult.ok(facts, evidence, List.copyOf(missing), ctx.dataRevision());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> safeList(List<String> value) {
        return value == null ? List.of() : List.copyOf(value);
    }
}
