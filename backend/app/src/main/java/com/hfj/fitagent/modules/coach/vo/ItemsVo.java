package com.hfj.fitagent.modules.coach.vo;

import java.util.List;

/**
 * {@code messages} 与 {@code steps} 的响应壳（§11.1 规范化决定第 4 条：统一 {@code \{items:[…]\}}，
 * 不返回裸数组）。
 *
 * <p>刻意<b>不</b>复用 {@code PageResult}：这两个接口的语义是"增量拉取"（{@code after} / 全量 steps），
 * 没有页码也没有总数；给出恒为 1 的 page 与恒为 0 的 total 是制造一个会漂移的假分页契约。
 */
public record ItemsVo<T>(List<T> items) {

    public static <T> ItemsVo<T> of(List<T> items) {
        return new ItemsVo<>(items == null ? List.of() : List.copyOf(items));
    }

    public static <T> ItemsVo<T> empty() {
        return new ItemsVo<>(List.of());
    }
}
