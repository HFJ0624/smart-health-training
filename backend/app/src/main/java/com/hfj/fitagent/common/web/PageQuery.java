package com.hfj.fitagent.common.web;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;

import java.util.List;
import java.util.Locale;

/**
 * 分页与排序入参。排序字段必须命中白名单，非法即拒绝——不给任何 SQL 拼接入口。
 */
public record PageQuery(int page, int size, String sortField, Direction direction) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public enum Direction {
        ASC, DESC;

        public static Direction parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return DESC;
            }
            String v = raw.trim().toUpperCase(Locale.ROOT);
            if ("ASC".equals(v)) {
                return ASC;
            }
            if ("DESC".equals(v)) {
                return DESC;
            }
            throw validation("sort", "INVALID_SORT_DIRECTION", "排序方向只支持 asc 或 desc", raw);
        }
    }

    /**
     * @param sort          形如 {@code measuredAt,desc}；大小写不敏感地解析方向
     * @param allowedFields 该接口允许的排序字段（请求侧命名）
     * @param defaultField  未提供 sort 时的默认排序字段，必须在 allowedFields 中
     */
    public static PageQuery of(Integer page, Integer size, String sort, List<String> allowedFields, String defaultField) {
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? DEFAULT_SIZE : size;
        if (s > MAX_SIZE) {
            throw validation("size", "SIZE_OUT_OF_RANGE", "每页数量不得超过 " + MAX_SIZE, size);
        }
        String field = defaultField;
        Direction direction = Direction.DESC;
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            String requested = parts[0].trim();
            if (!allowedFields.contains(requested)) {
                throw validation("sort", "INVALID_SORT_FIELD", "排序字段不在允许列表中", sort);
            }
            field = requested;
            direction = parts.length > 1 ? Direction.parse(parts[1]) : Direction.DESC;
        }
        if (!allowedFields.contains(field)) {
            throw new IllegalStateException("默认排序字段必须位于白名单内: " + defaultField);
        }
        return new PageQuery(p, s, field, direction);
    }

    /**
     * 请求侧字段名到数据库列名的映射由调用方提供，避免把属性名直接当成列名。
     */
    public String column(SortColumns columns) {
        return columns.columnOf(sortField);
    }

    public int offset() {
        return (page - 1) * size;
    }

    private static BizException validation(String field, String code, String message, Object rejected) {
        return new BizException(ErrorCode.VALIDATION_FAILED, message, List.of(FieldError.of(field, code, message, rejected)));
    }
}
