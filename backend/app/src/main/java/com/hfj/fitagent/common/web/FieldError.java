package com.hfj.fitagent.common.web;

/**
 * 结构化字段错误。field 使用请求体属性名或导入的 JSON 路径（如 days[2].items[0].reps_max）。
 */
public record FieldError(String field, String code, String message, Object rejected) {

    public static FieldError of(String field, String code, String message) {
        return new FieldError(field, code, message, null);
    }

    public static FieldError of(String field, String code, String message, Object rejected) {
        return new FieldError(field, code, message, rejected);
    }
}
