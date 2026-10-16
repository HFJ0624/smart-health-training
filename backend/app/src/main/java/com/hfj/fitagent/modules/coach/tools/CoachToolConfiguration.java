package com.hfj.fitagent.modules.coach.tools;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 本轮工具注册面的<b>唯一声明处</b>。
 *
 * <p>{@code agent.tool.ToolRegistry} 从容器里收集所有 {@code AgentTool} Bean（重名即启动失败），
 * 而"模型能看见哪几个"由这里的白名单决定：白名单外的工具<b>连声明都不发出去</b>
 * （{@code AgentRunner.declaredTools}），不是"发出去再拒绝调用"。
 *
 * <p>为什么只有三个（design §1 / baseline §3.3 的逐条核查结论）：
 * <table border="1">
 *   <caption>本轮工具面</caption>
 *   <tr><th>工具</th><th>注册</th><th>依据</th></tr>
 *   <tr><td>{@code get_profile}</td><td>✅</td>
 *       <td>档案与 ACTIVE 目标服务已存在且有测试（{@code UserProfileServiceTest} 13、{@code UserGoalServiceTest} 20）</td></tr>
 *   <tr><td>{@code get_training_plan}</td><td>✅</td>
 *       <td>{@code TrainingPlanService.findToday} 已存在，四种真实形态都有覆盖</td></tr>
 *   <tr><td>{@code get_weight_trend}</td><td>✅</td>
 *       <td>{@code body_measurement} 有真实序列，{@code BodyMeasurementService} 已交付</td></tr>
 *   <tr><td>{@code get_workout_records}</td><td>❌</td>
 *       <td>{@code workout_session/entry/set} 三张表与相应业务<b>不存在</b>（P1 刻意不做，见 docs/p1-scope.md §2）</td></tr>
 *   <tr><td>{@code get_training_progress}</td><td>❌</td>
 *       <td>没有执行记录、没有 metrics 服务：完成率有分母（计划）没分子（执行），算出来必然是假的</td></tr>
 *   <tr><td>{@code get_weekly_training_summary}</td><td>❌</td>
 *       <td>同上；周汇总需要"这周实际练了什么"，而本轮只有"这周计划练什么"</td></tr>
 * </table>
 *
 * <p><b>绝不编造训练结果</b>：宁可不注册，也不写一个返回空数组的占位工具让模型去解读——
 * "没有正式记录"不等于"实际没训练"（design §9），而一个假成功的工具结果会让整份回答失去可信度。
 * 要闭环它需要先做训练执行记录业务（独立迭代）。
 */
@Configuration
public class CoachToolConfiguration {

    /**
     * 本轮允许的工具名集合。
     *
     * <p>它是 {@code AgentContext.allowedTools} 的来源，因此也是"模型能力面"的服务端权威值：
     * 历史消息、工具返回文本、模型自由文本都没有任何路径修改它（design §8）。
     */
    @Bean(name = "coachAllowedTools")
    public Set<String> coachAllowedTools() {
        // 由 CoachToolNames.ALL 派生：再加工具时只需要改那一处 + 注册一个 AgentTool Bean
        return CoachToolNames.ALL;
    }
}
