package com.hfj.fitagent.common.web;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;

/**
 * 分页结果。total 来自数据库 count，hasNext 由页码与总数推导。
 */
public record PageResult<T>(int page, int size, long total, boolean hasNext, List<T> items) {

    public static <E, T> PageResult<T> from(IPage<E> p, List<T> items) {
        long total = p.getTotal();
        int page = (int) p.getCurrent();
        int size = (int) p.getSize();
        boolean hasNext = (long) page * size < total;
        return new PageResult<>(page, size, total, hasNext, items);
    }

    /** 内存分页（用于聚合根下的子结构裁剪），不用于替代数据库分页。 */
    public static <T> PageResult<T> of(PageQuery q, long total, List<T> items) {
        boolean hasNext = (long) q.page() * q.size() < total;
        return new PageResult<>(q.page(), q.size(), total, hasNext, items);
    }
}
