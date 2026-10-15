package com.hfj.fitagent.agent.context;

import com.hfj.fitagent.agent.AgentTestSupport;
import com.hfj.fitagent.agent.core.AgentContext;
import com.hfj.fitagent.agent.core.AgentProtocolException;
import com.hfj.fitagent.agent.core.RunLimits;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.MessageRole;
import com.hfj.fitagent.agent.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文装配验收（docs/agent-v1-design.md §8）。
 *
 * <p>重点是三件"结构性"的事：孤儿 tool 消息不可能发出、未闭合工具组不可能被裁开、
 * 历史与用户备注是非权威文本（既不改变权限，也不能替代一次真实读取）。
 */
class ContextManagerTest {

    private final AgentContext context = AgentTestSupport.context(RunLimits.defaults());

    private ContextManager manager(ContextBudget budget) {
        return new ContextManager(AgentTestSupport.templates(), budget);
    }

    @Test
    @DisplayName("顺序是 system(模板+服务端块) → 历史 → 当前问题；块里带业务日期与时区")
    void buildsDocumentedOrderAndServerDateBlock() {
        ConversationContext conversation = manager(ContextBudget.defaults()).open(context,
                "我最近体重多少？",
                List.of(new PriorTurn("上周我问过热身", "一般 5–10 分钟。")));

        List<ModelMessage> messages = conversation.messages();
        assertThat(messages.get(0).role()).isEqualTo(MessageRole.SYSTEM);
        assertThat(messages.get(0).content())
                .contains("个人事实必须来自工具")
                .contains("业务日期=" + AgentTestSupport.BUSINESS_DATE)
                .contains("用户时区=" + AgentTestSupport.ZONE.getId())
                .contains("数据修订号=" + AgentTestSupport.DATA_REVISION);
        assertThat(messages.get(1).role()).isEqualTo(MessageRole.USER);
        assertThat(messages.get(2).role()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(messages.get(2).content()).contains("非权威");
        assertThat(messages.get(3).role()).isEqualTo(MessageRole.USER);
        assertThat(messages.get(3).content()).contains("我最近体重多少？");
        assertThat(conversation.contextTruncated()).isFalse();
    }

    @Test
    @DisplayName("服务端块不含任何用户个人数据：个人事实只能来自工具")
    void serverBlockCarriesNoPersonalData() {
        ConversationContext conversation = manager(ContextBudget.defaults())
                .open(context, "我的计划是什么？", List.of());

        String system = conversation.messages().get(0).content();
        assertThat(system).doesNotContain("71.5").doesNotContain("kg").doesNotContain("userId=");
        assertThat(system).doesNotContain("sk-");
    }

    @Test
    @DisplayName("历史只取 user + 最终可见 assistant：出现工具消息就是协议错误")
    void historyRejectsToolMessagesFromPreviousRuns() {
        PriorTurn turn = new PriorTurn("问", "答");
        ConversationContext conversation = manager(ContextBudget.defaults()).open(context, "新问题", List.of(turn));

        assertThatThrownBy(() -> conversation.appendHistoryTurn(List.of(
                ModelMessage.tool("old-call", "get_profile", "{}"))))
                .isInstanceOf(AgentProtocolException.class);
    }

    @Test
    @DisplayName("超出历史轮数上限时只保留最近若干轮")
    void priorTurnsAreBounded() {
        List<PriorTurn> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new PriorTurn("问题" + i, "回答" + i));
        }

        ConversationContext conversation = manager(new ContextBudget(3, 800, 20000))
                .open(context, "现在的问题", many);

        assertThat(conversation.historyTurns()).isEqualTo(3);
        List<ModelMessage> messages = conversation.messages();
        assertThat(messages.stream().filter(m -> m.content().startsWith("（历史）问题11")).findFirst()).isPresent();
        assertThat(messages.stream().filter(m -> m.content().startsWith("（历史）问题0")).findFirst()).isEmpty();
    }

    @Test
    @DisplayName("assistant(tool_calls)+tool 配对合法；孤儿 tool 与未闭合组都被拒绝发出")
    void pairingValidationCatchesOrphansAndHalfGroups() {
        ConversationContext conversation = manager(ContextBudget.defaults()).open(context, "我的体重", List.of());

        conversation.appendAssistant(ModelMessage.assistantToolCalls("",
                List.of(new ToolCall("c1", "get_weight_trend", "{\"days\":7}"))));
        assertThatThrownBy(conversation::assertProtocolValid)
                .isInstanceOfSatisfying(AgentProtocolException.class,
                        e -> assertThat(e.getMessage()).contains("未闭合"));

        conversation.appendTool(ModelMessage.tool("c1", "get_weight_trend", "{\"code\":\"OK\"}"));
        assertThatCode(conversation::assertProtocolValid).doesNotThrowAnyException();

        ConversationContext orphan = manager(ContextBudget.defaults()).open(context, "我的体重", List.of());
        orphan.appendTool(ModelMessage.tool("ghost", "get_weight_trend", "{}"));
        assertThatThrownBy(orphan::assertProtocolValid)
                .isInstanceOfSatisfying(AgentProtocolException.class,
                        e -> assertThat(e.getMessage()).contains("孤儿"));
    }

    @Test
    @DisplayName("同一 tool_call_id 出现两条结果 = 协议错误（不能悄悄覆盖）")
    void duplicateToolResultIsRejected() {
        ConversationContext conversation = manager(ContextBudget.defaults()).open(context, "我的体重", List.of());
        conversation.appendAssistant(ModelMessage.assistantToolCalls("",
                List.of(new ToolCall("dup", "get_weight_trend", "{}"))));
        conversation.appendTool(ModelMessage.tool("dup", "get_weight_trend", "第一份"));
        conversation.appendTool(ModelMessage.tool("dup", "get_weight_trend", "第二份"));

        assertThatThrownBy(conversation::assertProtocolValid)
                .isInstanceOfSatisfying(AgentProtocolException.class,
                        e -> assertThat(e.getMessage()).contains("同一 tool_call_id"));
    }

    @Test
    @DisplayName("超出上下文预算时整轮丢弃并置 contextTruncated，绝不裁开工具组")
    void trimmingDropsWholeTurnsOnly() {
        ContextBudget tight = new ContextBudget(6, 100, 260);
        ConversationContext conversation = manager(tight)
                .open(context, "现在的问题", List.of(new PriorTurn("很久以前的问题", "很久以前的回答")));

        assertThat(conversation.contextTruncated()).isTrue();
        assertThat(conversation.messages()).noneMatch(message -> message.content().contains("很久以前的回答"));
        // 问题与系统块永远保留
        assertThat(conversation.messages().get(0).role()).isEqualTo(MessageRole.SYSTEM);
        assertThat(conversation.messages()).anyMatch(message -> message.content().contains("现在的问题"));

        // 工具组必须同进同出：一组完整配对存在时不允许把它裁开
        ConversationContext withTools = manager(new ContextBudget(6, 100, 240)).open(context, "问题", List.of());
        withTools.appendAssistant(ModelMessage.assistantToolCalls("",
                List.of(new ToolCall("keep-1", "get_weight_trend", "{\"days\":7}"))));
        withTools.appendTool(ModelMessage.tool("keep-1", "get_weight_trend", "结果内容".repeat(30)));

        assertThat(withTools.messages().stream()
                .filter(message -> message.role() == MessageRole.TOOL).count()).isEqualTo(1);
        assertThatCode(withTools::assertProtocolValid).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("空问题不接受：装配阶段就失败，而不是发一个没有问题的请求")
    void blankQuestionIsRejected() {
        assertThatThrownBy(() -> manager(ContextBudget.defaults()).open(context, "  ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
