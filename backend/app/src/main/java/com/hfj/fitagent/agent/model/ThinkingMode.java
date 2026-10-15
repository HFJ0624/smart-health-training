package com.hfj.fitagent.agent.model;

/**
 * 思考模式开关。
 *
 * <p>本轮只允许 {@code DISABLED}。原因（docs/agent-v1-protocol.md §5）：官方默认是 <b>enabled</b>，
 * 而带 {@code tools} 的请求必须把 {@code reasoning_content} 完整回传，否则**直接 400**。
 * 本项目不实现该回传，所以：
 * <ul>
 *   <li>每个请求显式发送 {@code thinking.type=disabled}，不依赖供应商默认值；</li>
 *   <li>配置层与请求工厂双重拒绝 {@code ENABLED}——"开着但不回传字段"是丢字段骗人，不是可用能力。</li>
 * </ul>
 * 枚举保留 {@code ENABLED} 常量只为了让"它存在但被拒绝"这件事在代码里可见（错误信息可引用它）。
 */
public enum ThinkingMode {

    ENABLED("enabled"),
    DISABLED("disabled");

    private final String wireName;

    ThinkingMode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ThinkingMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return DISABLED;
        }
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (ThinkingMode mode : values()) {
            if (mode.wireName.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("fitagent.agent.model.deepseek.thinking 只能是 enabled 或 disabled，当前值："
                + raw.trim());
    }
}
