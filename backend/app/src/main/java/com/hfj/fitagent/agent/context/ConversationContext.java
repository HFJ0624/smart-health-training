package com.hfj.fitagent.agent.context;

import com.hfj.fitagent.agent.core.AgentProtocolException;
import com.hfj.fitagent.agent.model.ModelMessage;
import com.hfj.fitagent.agent.model.MessageRole;
import com.hfj.fitagent.agent.model.ToolCall;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本轮的工作上下文。
 *
 * <p>结构分成四个区，裁剪语义各不相同（这是本类存在的唯一理由）：
 * <pre>
 *   [system]  模板 + 服务端日期/时区块        —— 永不丢弃
 *   [history] 有界历史轮（user + 最终可见 assistant）—— 整轮丢弃，最老的先丢
 *   [question] 本轮问题                       —— 永不丢弃
 *   [working] 本轮 assistant/tool 消息        —— 按<b>块</b>丢弃，块 = assistant(含 tool_calls) + 其全部 tool 消息
 * </pre>
 *
 * <p>三条来自协议与设计的硬规则（docs/agent-v1-protocol.md §3、docs/agent-v1-design.md §5.3、§8）：
 * <ol>
 *   <li><b>绝不发出孤儿 tool 消息</b>：每条 tool 消息的 tool_call_id 必须属于紧邻的前一条 assistant 声明；</li>
 *   <li><b>绝不裁掉未闭合的工具组</b>：未闭合就停手，宁可暂时超一点预算——
 *       半个工具组会让供应商当场 400 并白烧一次调用；</li>
 *   <li><b>超限整块丢弃</b>并置 {@code contextTruncated}，不做字符级截断：截断一半的 JSON 结果
 *       会教模型学出更差的输出，也把"哪些数字来自工具"糊掉。</li>
 * </ol>
 *
 * <p>非线程安全：一个 run 一个实例，单线程顺序构造。
 */
public final class ConversationContext {

    private final ModelMessage systemMessage;
    private final List<List<ModelMessage>> historyTurns = new ArrayList<>();
    private final ModelMessage questionMessage;
    private final List<ModelMessage> working = new ArrayList<>();
    private final ContextBudget budget;
    private boolean truncated;

    ConversationContext(ModelMessage systemMessage, ModelMessage questionMessage, ContextBudget budget) {
        if (systemMessage == null || systemMessage.role() != MessageRole.SYSTEM) {
            throw new IllegalArgumentException("上下文必须以 system 模板消息开头");
        }
        if (questionMessage == null || questionMessage.role() != MessageRole.USER) {
            throw new IllegalArgumentException("本轮问题必须是 user 消息");
        }
        this.systemMessage = systemMessage;
        this.questionMessage = questionMessage;
        this.budget = budget == null ? ContextBudget.defaults() : budget;
    }

    /** 追加一整轮历史（user + 最终可见 assistant）。 */
    void appendHistoryTurn(List<ModelMessage> turn) {
        if (turn == null || turn.isEmpty()) {
            return;
        }
        List<ModelMessage> copy = new ArrayList<>(turn);
        copy.forEach(this::rejectToolMessageInHead);
        historyTurns.add(copy);
        trimToBudget();
    }

    public void appendAssistant(ModelMessage assistant) {
        if (assistant.role() != MessageRole.ASSISTANT) {
            throw new IllegalArgumentException("只有 assistant 消息可以这样追加");
        }
        working.add(assistant);
        trimToBudget();
    }

    public void appendTool(ModelMessage tool) {
        if (tool.role() != MessageRole.TOOL) {
            throw new IllegalArgumentException("只有 tool 消息可以这样追加");
        }
        working.add(tool);
        trimToBudget();
    }

    /** 发出请求前的配对自检：孤儿 tool 消息、缺配对、重复配对都会在这里当场失败。 */
    public void assertProtocolValid() {
        List<ModelMessage> all = messages();
        Set<String> openIds = null;
        Set<String> pending = null;
        for (int i = 0; i < all.size(); i++) {
            ModelMessage message = all.get(i);
            if (message.role() == MessageRole.TOOL) {
                if (openIds == null || pending == null) {
                    throw new AgentProtocolException("第 " + i + " 条是孤儿 tool 消息：没有正在等待结果的工具调用"
                            + "（agent-v1-protocol.md §3 回传要求）");
                }
                String id = message.toolCallId();
                if (!openIds.contains(id)) {
                    throw new AgentProtocolException("tool 消息的 tool_call_id 不属于紧邻的 assistant 工具调用：" + id);
                }
                if (!pending.remove(id)) {
                    throw new AgentProtocolException("同一 tool_call_id 出现多条结果消息：" + id);
                }
                continue;
            }
            if (pending != null && !pending.isEmpty()) {
                throw new AgentProtocolException("工具组未闭合：仍有 " + pending.size()
                        + " 个 tool_call_id 没有对应结果，不能发出请求");
            }
            if (message.role() == MessageRole.ASSISTANT && !message.toolCalls().isEmpty()) {
                openIds = new LinkedHashSet<>();
                for (ToolCall call : message.toolCalls()) {
                    if (!openIds.add(call.id())) {
                        throw new AgentProtocolException("同一次响应出现重复 tool_call_id：" + call.id());
                    }
                }
                pending = new LinkedHashSet<>(openIds);
            } else {
                openIds = null;
                pending = null;
            }
        }
        if (pending != null && !pending.isEmpty()) {
            throw new AgentProtocolException("上下文以未闭合的工具组结尾：" + pending);
        }
    }

    /** 发给供应商的消息序列。 */
    public List<ModelMessage> messages() {
        List<ModelMessage> all = new ArrayList<>(working.size() + historyTurns.size() * 2 + 2);
        all.add(systemMessage);
        historyTurns.forEach(all::addAll);
        all.add(questionMessage);
        all.addAll(working);
        return List.copyOf(all);
    }

    public boolean contextTruncated() {
        return truncated;
    }

    public int size() {
        return messages().size();
    }

    public int historyTurns() {
        return historyTurns.size();
    }

    private void rejectToolMessageInHead(ModelMessage message) {
        if (message.role() == MessageRole.TOOL || !message.toolCalls().isEmpty()) {
            throw new AgentProtocolException("历史区只允许纯文本 user/assistant 消息："
                    + "上一轮的工具消息不得进入新请求（design §8）");
        }
    }

    private void trimToBudget() {
        while (estimatedChars() > budget.maxContextChars()) {
            if (!dropOldest()) {
                return;
            }
        }
    }

    /**
     * 丢弃最老的可丢内容；返回是否真的丢了东西。
     *
     * <p>顺序：先丢最老的历史轮（它离本轮结论最远），再丢最老的工作块。
     * 工作块里最后追加的那块<b>不丢</b>——那是模型刚拿到的工具结果，丢了就等于让它凭空作答。
     */
    private boolean dropOldest() {
        if (!historyTurns.isEmpty()) {
            historyTurns.remove(0);
            truncated = true;
            return true;
        }
        int blockStart = firstDroppableBlockStart();
        if (blockStart < 0) {
            return false;
        }
        int blockEnd = blockEnd(blockStart);
        if (blockEnd <= blockStart || !isBlockSealed(blockStart)) {
            return false;
        }
        // 只剩最后一块时停手：那块是本轮刚刚产生的工具结果
        if (blockEnd >= working.size()) {
            return false;
        }
        for (int i = blockStart; i < blockEnd; i++) {
            working.remove(blockStart);
        }
        truncated = true;
        return true;
    }

    /** 最老的可丢块总是从索引 0 开始；是否允许丢由 {@link #dropOldest()} 里的"最后一块不丢"判定。 */
    private int firstDroppableBlockStart() {
        return working.isEmpty() ? -1 : 0;
    }

    private int blockEnd(int start) {
        ModelMessage head = working.get(start);
        if (head.toolCalls().isEmpty()) {
            return start + 1;
        }
        int index = start + 1;
        while (index < working.size() && working.get(index).role() == MessageRole.TOOL) {
            index++;
        }
        return index;
    }

    private boolean isBlockSealed(int start) {
        ModelMessage head = working.get(start);
        if (head.toolCalls().isEmpty()) {
            return true;
        }
        Set<String> expected = new LinkedHashSet<>();
        head.toolCalls().forEach(call -> expected.add(call.id()));
        int index = start + 1;
        while (index < working.size() && working.get(index).role() == MessageRole.TOOL) {
            expected.remove(working.get(index).toolCallId());
            index++;
        }
        return expected.isEmpty();
    }

    private int estimatedChars() {
        int chars = estimateChars(systemMessage) + estimateChars(questionMessage);
        for (List<ModelMessage> turn : historyTurns) {
            for (ModelMessage message : turn) {
                chars += estimateChars(message);
            }
        }
        for (ModelMessage message : working) {
            chars += estimateChars(message);
        }
        return chars;
    }

    private static int estimateChars(ModelMessage message) {
        int chars = message.content().length() + message.role().wireName().length() + 8;
        for (ToolCall call : message.toolCalls()) {
            chars += call.name().length() + call.argumentsJson().length() + call.id().length() + 8;
        }
        return chars;
    }
}
