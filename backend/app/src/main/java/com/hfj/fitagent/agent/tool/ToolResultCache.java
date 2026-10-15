package com.hfj.fitagent.agent.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * <b>单次 run 内</b>的工具结果复用表（docs/agent-v1-design.md §4）。
 *
 * <p>键是 {@code (toolName, 规范化参数, dataRevision)}：同一份已验证的事实在同一 run 里不必重复读库，
 * 但复用<b>只省掉执行</b>，不省协议——新的 tool_call_id 仍然要发一条对应的 tool 消息，
 * 否则上下文里就会出现孤儿 id。
 *
 * <p>"连续两轮命中同一个键"由 Runner 判定为有界循环（{@code REPETITIVE_LOOP}）并停止：
 * 缓存绝不能变成让模型空转烧钱的许可，所以本类只记录<b>请求次数</b>，不做任何"再给一次机会"的判断。
 *
 * <p>非线程安全且刻意如此：首版顺序执行工具（design §14），一个 run 一个实例。
 */
public final class ToolResultCache {

    private final Map<String, ExecutedTool> byKey = new LinkedHashMap<>();
    private final Map<String, Integer> requestCountByKey = new LinkedHashMap<>();

    /**
     * 命中即计数：每次"请求同一个键"都会让计数器 +1，无论是否命中缓存。
     * 未命中时调用方执行后必须 {@link #put}。
     */
    public Optional<ExecutedTool> find(String cacheKey) {
        int requests = requestCountByKey.merge(cacheKey, 1, Integer::sum);
        ExecutedTool cached = byKey.get(cacheKey);
        if (cached == null) {
            return Optional.empty();
        }
        return Optional.of(cached.withHitCount(requests));
    }

    /** 复放缓存结果（未重新执行工具）时也要登记计数：计数器只由 {@link #find} 递增，避免一次调用被计两次。 */
    public void put(String cacheKey, ExecutedTool executed) {
        byKey.put(cacheKey, executed);
        requestCountByKey.putIfAbsent(cacheKey, 1);
    }

    /** 本 run 内该键被请求过几次（1 = 首次真实执行）。 */
    public int requestCount(String cacheKey) {
        return requestCountByKey.getOrDefault(cacheKey, 0);
    }

    public int size() {
        return byKey.size();
    }
}
