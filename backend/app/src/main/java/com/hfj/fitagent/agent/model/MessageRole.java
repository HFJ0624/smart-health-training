package com.hfj.fitagent.agent.model;

import java.util.Locale;
import java.util.Optional;

/**
 * 消息角色，与 Chat Completions 的四种角色一一对应（docs/agent-v1-protocol.md §3）。
 *
 * 用枚举而不是裸字符串：角色写错（比如把 tool 写成 assistant）会当场破坏 tool_call_id 配对，
 * 而这种错误一旦进入供应商请求就是不可诊断的 400/静默误解。
 */
public enum MessageRole {

    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireName;

    MessageRole(String wireName) {
        this.wireName = wireName;
    }

    /** 进入请求体的值，逐字符固定小写。 */
    public String wireName() {
        return wireName;
    }

    public static Optional<MessageRole> fromWire(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MessageRole role : values()) {
            if (role.wireName.equals(normalized)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
