package com.hfj.fitagent.agent.model;

/**
 * 发给供应商的 {@code tool_choice}。
 *
 * 只有两个取值可用：本轮显式关闭思考模式（docs/agent-v1-protocol.md §5），
 * 而 {@code required} 与"指定某个工具名"在思考模式下会 400；即使关了思考，
 * 我们也刻意不允许这两个取值——一旦放开就等于允许模型被逼着调用一个可能并不存在的工具。
 * 用枚举让非法值在类型层不可表示，而不是靠运行时约定。
 */
public enum ToolChoice {

    AUTO("auto"),
    NONE("none");

    private final String wireName;

    ToolChoice(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
