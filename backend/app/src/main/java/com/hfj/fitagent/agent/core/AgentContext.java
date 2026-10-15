package com.hfj.fitagent.agent.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

/**
 * 一次 run 的服务端可信上下文（docs/agent-v1-design.md §3）。
 *
 * <p>三条结构性保证，比"每处记得校验"更可靠：
 * <ol>
 *   <li><b>身份只来自服务端</b>：userId / conversationId / runId 由装配方从
 *       {@code AuthenticatedUsers.require()} 与 agent_run 行取出，工具参数里没有这些字段，
 *       所以"前端传 userId 决定身份"在本层不可表示。</li>
 *   <li><b>不持有明文凭据</b>：只有 {@link CredentialHandle}；解密发生在发出 HTTP 的一行代码里。</li>
 *   <li><b>能力白名单</b>：allowedTools 由服务端按目录与用户凭据状态算出，模型文本与工具结果都改不了它
 *       （docs/agent-v1-design.md §8）。</li>
 * </ol>
 *
 * @param dataRevision run <b>开始时</b>的数据修订号；结束时再取一次，不同则结果标记为陈旧
 * @param deadline     run 的硬截止时间，传输超时由它推导（protocol §9）
 */
public record AgentContext(long runId,
                           String runUuid,
                           long userId,
                           long conversationId,
                           ZoneId zone,
                           LocalDate businessDate,
                           long dataRevision,
                           CredentialHandle credential,
                           ModelSelection model,
                           BudgetReference budget,
                           Instant deadline,
                           Set<String> allowedTools) {

    public AgentContext {
        if (runId <= 0) {
            throw new IllegalArgumentException("runId 必须为正数（来自 agent_run.id）");
        }
        if (runUuid == null || runUuid.isBlank()) {
            throw new IllegalArgumentException("runUuid 不能为空");
        }
        runUuid = runUuid.trim();
        if (userId <= 0) {
            throw new IllegalArgumentException("userId 必须来自认证上下文");
        }
        if (conversationId <= 0) {
            throw new IllegalArgumentException("conversationId 必须为正数");
        }
        if (zone == null) {
            throw new IllegalArgumentException("业务时区不能为空（CurrentUser.zone()）");
        }
        if (businessDate == null) {
            throw new IllegalArgumentException("业务日期必须由服务端 AppClock + 时区推导");
        }
        if (deadline == null) {
            throw new IllegalArgumentException("run 必须有硬截止时间");
        }
        if (model == null) {
            throw new IllegalArgumentException("模型选择必须来自 model_catalog，不能为空");
        }
        allowedTools = allowedTools == null || allowedTools.isEmpty()
                ? Set.of()
                : Set.copyOf(allowedTools);
        budget = budget == null ? BudgetReference.unset() : budget;
    }

    /** 没有凭据时不得发起任何远程调用：处置是提示用户配置，而不是换一把 Key 或走默认凭据。 */
    public boolean hasCredential() {
        return credential != null;
    }

    /** 模型能不能被允许调用该工具：白名单是服务端集合，与模型/工具文本无关。 */
    public boolean allowsTool(String toolName) {
        return toolName != null && allowedTools.contains(toolName);
    }

    public boolean deadlinePassed(Instant now) {
        return !now.isBefore(deadline);
    }

    /** 剩余时间；已过期返回 {@link Duration#ZERO}（不返回负数，避免调用方把它传给 HttpClient）。 */
    public Duration remaining(Instant now) {
        Duration left = Duration.between(now, deadline);
        return left.isNegative() || left.isZero() ? Duration.ZERO : left;
    }

    /** 日志/轨迹用：不含任何密钥材料，也不含用户个人信息。 */
    @Override
    public String toString() {
        return "AgentContext[runId=" + runId + ", conversationId=" + conversationId
                + ", businessDate=" + businessDate + ", model=" + (model == null ? "<未选>" : model.modelCode())
                + ", allowedTools=" + allowedTools.size() + ", hasCredential=" + hasCredential() + "]";
    }
}
