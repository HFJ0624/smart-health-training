package com.hfj.fitagent.agent.core;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.model.ModelCapabilities;
import com.hfj.fitagent.agent.tool.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 核心契约的结构约束（docs/agent-v1-design.md §3）。
 *
 * <p>这一组断言的意义在于"结构上不可能"：上下文里没法放明文凭据、闸门数字缺失会回落而不是变成 0、
 * 停止原因与 run 状态只有一种推导关系。
 */
class AgentCoreContractTest {

    @Test
    @DisplayName("RunLimits 的默认值是文档初值，配置成 0/负数时回落而不是关掉")
    void runLimitsFallBackToDocumentedDefaults() {
        RunLimits limits = RunLimits.defaults();

        assertThat(limits.maxModelCalls()).isEqualTo(4);
        assertThat(limits.maxToolCalls()).isEqualTo(8);
        assertThat(limits.maxToolsPerRound()).isEqualTo(4);
        assertThat(limits.maxResultBytes()).isEqualTo(8192);
        assertThat(limits.maxOutputTokens()).isEqualTo(1024);
        assertThat(limits.deadlineSeconds()).isEqualTo(120);
        assertThat(new RunLimits(0, -1, 0, 0, 0, 0).maxModelCalls()).isEqualTo(4);
        assertThat(limits.deadline()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    @DisplayName("单轮上限大于总上限属于配置错误：启动即说明，不让它变成死代码")
    void inconsistentLimitsFailAtBinding() {
        assertThatThrownBy(() -> new RunLimits(4, 2, 8, 8192, 1024, 120))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("闸门判定：次数用完就不再允许，最后一次允许调用被识别出来")
    void gateArithmetic() {
        RunLimits limits = RunLimits.defaults();

        assertThat(limits.modelCallAllowed(3)).isTrue();
        assertThat(limits.modelCallAllowed(4)).isFalse();
        assertThat(limits.isLastAllowedCall(3)).isTrue();
        assertThat(limits.isLastAllowedCall(2)).isFalse();
        assertThat(limits.toolCallAllowed(7)).isTrue();
        assertThat(limits.toolCallAllowed(8)).isFalse();
        assertThat(limits.toolAllowedInRound(4)).isFalse();
        // 输出上限取"工程闸门"和"目录值"的较小者，代码里不许放大
        assertThat(limits.effectiveMaxOutputTokens(4096)).isEqualTo(1024);
        assertThat(limits.effectiveMaxOutputTokens(512)).isEqualTo(512);
    }

    @Test
    @DisplayName("AgentContext 不放明文凭据：toString 只出现句柄信息")
    void contextCannotCarryPlaintextKey() {
        AgentContext context = AgentTestSupport.context(RunLimits.defaults());

        assertThat(context.toString()).doesNotContain(AgentTestSupport.FAKE_API_KEY);
        assertThat(context.credential().toString()).doesNotContain(AgentTestSupport.FAKE_API_KEY);
        assertThat(context.hasCredential()).isTrue();
        assertThat(AgentTestSupport.contextNoCredential(RunLimits.defaults()).hasCredential()).isFalse();
    }

    @Test
    @DisplayName("白名单是不可变集合：模型或工具文本改不动它")
    void allowedToolsAreImmutable() {
        Set<String> mutable = new HashSet<>();
        mutable.add("get_profile");
        AgentContext context = AgentTestSupport.contextWithTools(mutable, RunLimits.defaults());
        mutable.add("get_anything");

        assertThat(context.allowsTool("get_profile")).isTrue();
        assertThat(context.allowsTool("get_anything")).as("外部集合的后续修改不得影响 run 的工具面").isFalse();
        assertThatThrownBy(() -> context.allowedTools().add("get_anything"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("剩余时间不为负；截止时间一到即为 0")
    void remainingTimeIsClamped() {
        AgentContext context = AgentTestSupport.context(RunLimits.defaults());

        assertThat(context.remaining(AgentTestSupport.NOW)).isEqualTo(Duration.ofSeconds(120));
        // 固定时钟 NOW=02:00:00Z、截止=NOW+120s，所以"还剩 1 秒"的时刻是 02:01:59Z
        assertThat(context.remaining(Instant.parse("2026-10-13T02:01:59Z"))).isEqualTo(Duration.ofSeconds(1));
        assertThat(context.remaining(Instant.parse("2026-10-13T03:00:00Z"))).isEqualTo(Duration.ZERO);
        assertThat(context.deadlinePassed(Instant.parse("2026-10-13T03:00:00Z"))).isTrue();
    }

    @Test
    @DisplayName("上下文的必填项来自服务端：任何一项缺失都构造不出来")
    void contextRequiresServerSideValues() {
        AgentContext base = AgentTestSupport.context(RunLimits.defaults());

        assertThatThrownBy(() -> new AgentContext(base.runId(), " ", base.userId(), base.conversationId(),
                base.zone(), base.businessDate(), base.dataRevision(), base.credential(), base.model(),
                base.budget(), base.deadline(), base.allowedTools()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentContext(base.runId(), base.runUuid(), 0L, base.conversationId(),
                base.zone(), base.businessDate(), base.dataRevision(), base.credential(), base.model(),
                base.budget(), base.deadline(), base.allowedTools()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentContext(base.runId(), base.runUuid(), base.userId(), base.conversationId(),
                null, base.businessDate(), base.dataRevision(), base.credential(), base.model(),
                base.budget(), base.deadline(), base.allowedTools()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("模型选择必须来自目录：模型代码与输出上限都是必填")
    void modelSelectionIsCatalogBacked() {
        assertThatThrownBy(() -> new ModelSelection("DEEPSEEK", 3L, " ",
                ModelCapabilities.toolsOnly(), 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelSelection("DEEPSEEK", 3L, "deepseek-flash",
                ModelCapabilities.toolsOnly(), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("停止原因到 run 状态的映射只有一条路径")
    void stopReasonMapsToRunStatus() {
        assertThat(StopReason.SUCCEEDED.runStatus()).isEqualTo("SUCCEEDED");
        assertThat(StopReason.NO_CREDENTIAL.runStatus()).isEqualTo("WAITING_CREDENTIAL");
        assertThat(StopReason.BUDGET_STOPPED.runStatus()).isEqualTo("BUDGET_STOPPED");
        assertThat(StopReason.LIMIT_STOPPED.runStatus()).isEqualTo("LIMIT_STOPPED");
        assertThat(StopReason.DEADLINE_STOPPED.runStatus()).isEqualTo("DEADLINE_STOPPED");
        assertThat(StopReason.CANCELLED.runStatus()).isEqualTo("CANCELLED");
        assertThat(StopReason.PROVIDER_ERROR.runStatus()).isEqualTo("FAILED");
        assertThat(StopReason.REPETITIVE_LOOP.runStatus()).isEqualTo("FAILED");
        assertThat(StopReason.PROTOCOL_INCOMPLETE.runStatus()).isEqualTo("FAILED");
        assertThat(StopReason.LOOP_EXHAUSTED.runStatus()).isEqualTo("FAILED");
        assertThat(StopReason.values()).hasSize(10);
    }

    @Test
    @DisplayName("取消是幂等的，并且会把钩子带给在途动作")
    void cancellationIsIdempotentAndFiresHook() {
        RunCancellation cancellation = new RunCancellation();
        java.util.concurrent.atomic.AtomicInteger fired = new java.util.concurrent.atomic.AtomicInteger();

        cancellation.attachInFlight(fired::incrementAndGet);
        cancellation.request();
        cancellation.request();

        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(fired.get()).isEqualTo(1);

        RunCancellation late = new RunCancellation();
        late.request();
        java.util.concurrent.atomic.AtomicInteger lateFired = new java.util.concurrent.atomic.AtomicInteger();
        // 已经取消之后再注册的钩子必须立刻执行，否则刚建立的连接就漏掉了取消信号
        late.attachInFlight(lateFired::incrementAndGet);
        assertThat(lateFired.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("凭据句柄只有定位信息，provider 码按目录口径大写规范化")
    void credentialHandleNormalizesProvider() {
        assertThat(new CredentialHandle(1L, 3, " deepseek ").providerId()).isEqualTo("DEEPSEEK");
        assertThatThrownBy(() -> new CredentialHandle(0L, 1, "DEEPSEEK")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CredentialHandle(1L, 0, "DEEPSEEK")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("evidenceId 由来源坐标派生：同坐标稳定，不同坐标不同")
    void evidenceIdIsDerivedAndStable() {
        Evidence first = new Evidence("body_measurement", "501", null, AgentTestSupport.BUSINESS_DATE, null, 17L);
        Evidence same = new Evidence("body_measurement", "501", null, AgentTestSupport.BUSINESS_DATE, null, 17L);
        Evidence otherRevision = new Evidence("body_measurement", "501", null, AgentTestSupport.BUSINESS_DATE,
                null, 18L);

        assertThat(first.evidenceId()).isEqualTo(same.evidenceId()).startsWith("ev-");
        assertThat(first.evidenceId()).hasSize(19);
        assertThat(first.evidenceId()).isNotEqualTo(otherRevision.evidenceId());
        assertThatThrownBy(() -> new Evidence("body_measurement", "501", null, null, null, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("预算引用不完整时，网关必须拒绝发起（不存在默认放行）")
    void budgetReferenceReportsIncompleteness() {
        assertThat(BudgetReference.unset().complete()).isFalse();
        assertThat(new BudgetReference(1L, 2L, "cny").complete()).isTrue();
        assertThat(new BudgetReference(1L, 2L, "cny").currency()).isEqualTo("CNY");
    }
}
