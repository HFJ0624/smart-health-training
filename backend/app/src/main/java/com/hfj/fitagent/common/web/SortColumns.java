package com.hfj.fitagent.common.web;

import com.hfj.fitagent.common.error.BizException;
import com.hfj.fitagent.common.error.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 请求字段名到数据库列名的显式映射表。排序列只允许来自这里，杜绝把属性名当列名或拼接进 SQL。
 */
public record SortColumns(Map<String, String> mapping) {

    public static SortColumns of(String... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("字段-列映射必须成对给出");
        }
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return new SortColumns(Map.copyOf(m));
    }

    public List<String> allowedFields() {
        return List.copyOf(mapping.keySet());
    }

    public String columnOf(String field) {
        String column = mapping.get(field);
        if (column == null) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "排序字段不在允许列表中",
                    List.of(FieldError.of("sort", "INVALID_SORT_FIELD", "排序字段不在允许列表中", field)));
        }
        return column;
    }
}
