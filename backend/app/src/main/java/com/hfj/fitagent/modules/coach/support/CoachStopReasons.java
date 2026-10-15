package com.hfj.fitagent.modules.coach.support;

import java.util.Locale;

/**
 * {@code agent_run.stop_reason}（枚举名）与前端 wire 值之间的翻译（docs/agent-v1-design.md §11.1 第 8 条）。
 *
 * <p>只有两个名字不同，其它原样透传：wire 用 {@code COMPLETED} 表示"拿到可用回答"，
 * 而执行循环内部的枚举叫 {@code SUCCEEDED}。未知值一律原样返回，让页面显示机器码——
 * 把不认识的原因猜成一个认识的原因，比显示原文更危险。
 */
public final class CoachStopReasons {

    /** 前端契约里"成功"的名字。 */
    public static final String WIRE_COMPLETED = "COMPLETED";

    private CoachStopReasons() {
    }

    public static String toWire(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) {
            return null;
        }
        String normalized = stopReason.trim().toUpperCase(Locale.ROOT);
        return "SUCCEEDED".equals(normalized) ? WIRE_COMPLETED : normalized;
    }

    /** wire/枚举两个写法都接受，用于持久化时固定成枚举名。 */
    public static String toDatabase(String stopReason) {
        if (stopReason == null || stopReason.isBlank()) {
            return null;
        }
        String normalized = stopReason.trim().toUpperCase(Locale.ROOT);
        return WIRE_COMPLETED.equals(normalized) ? "SUCCEEDED" : normalized;
    }
}
