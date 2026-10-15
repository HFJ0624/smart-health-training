package com.hfj.fitagent.modules.training;

import com.hfj.fitagent.common.web.FieldError;

/**
 * 一条校验发现（错误或警告）。
 *
 * <p>{@code path} 采用输入文档的 JSON 路径口径（数组用下标、键名用 snake_case），
 * 与 {@code docs/p1-plan-import.md} §8 的示例逐字对齐，页面可以直接按下标高亮到行；
 * 手工编辑接口用的是 camelCase 请求体，因此这里额外提供 {@link #dtoField()}，
 * 由同一份校验结果映射出 {@code VALIDATION_FAILED} 的字段错误——避免写两套规则。
 *
 * @param path  JSON 路径，例如 {@code days[2].items[0].reps_max}
 * @param code  字段级错误码，取值见 {@link PlanImportErrorCode}
 * @param message 面向人的中文说明，前端不解析（只 switch code）
 * @param value 被拒绝的原始值，可为 null（未知字段之类不回显内容）；
 *              值若是对象或数组会被折叠成 {@code {object}} / {@code [array]} 标记，见紧凑构造器
 */
public record PlanIssue(String path, String code, String message, Object value) {

    public PlanIssue {
        // 回显被拒绝的值时只回显标量：结构型值（对象/数组）改成类型名。
        // 否则一个放错位置的嵌套对象会把整份文档再抄一遍回给客户端——
        // 响应体积不合适，也等于把用户自己的输入原样吐回去当"错误详情"。
        value = scalarOnly(value);
    }

    private static Object scalarOnly(Object value) {
        if (value instanceof java.util.Map) {
            return "{object}";
        }
        if (value instanceof java.util.List) {
            return "[array]";
        }
        return value;
    }

    public static PlanIssue of(String path, String code, String message) {
        return new PlanIssue(path, code, message, null);
    }

    public static PlanIssue of(String path, String code, String message, Object value) {
        return new PlanIssue(path, code, message, value);
    }

    /**
     * 转成手工接口（camelCase 请求体）的字段错误路径：只把 snake_case 键名折叠成 camelCase，
     * 下标保持不动，例如 {@code days[2].items[0].target_sets -> days[2].items[0].targetSets}。
     */
    public String dtoField() {
        StringBuilder sb = new StringBuilder(path.length());
        boolean upNext = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_') {
                upNext = sb.length() > 0 && sb.charAt(sb.length() - 1) != '.';
                continue;
            }
            sb.append(upNext ? Character.toUpperCase(c) : c);
            upNext = false;
        }
        return sb.toString();
    }

    public FieldError toFieldError() {
        return FieldError.of(dtoField(), code, message, value);
    }
}
