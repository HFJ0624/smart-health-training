package com.hfj.fitagent.modules.training.vo;

/**
 * 导入校验的单条发现（错误或警告），形状与 docs/p1-plan-import.md §8 的示例逐字一致。
 *
 * <p>{@code path} 是 JSON 路径 + 数组下标（如 {@code days[2].items[0].reps_max}），
 * 页面据此逐行高亮；这比"字段名 + 提示语"更严：同一个键出现在 120 个条目里时，
 * 只有下标能告诉用户到底是哪一行。
 *
 * <p>{@code code} 是稳定的字段级机器码（见 {@code PlanImportErrorCode}），
 * {@code message} 只给人看，前端不解析。
 *
 * @param value 被拒绝的值；未知/禁用字段不回显内容，故可为 null
 */
public record ImportIssueVo(String path, String code, String message, Object value) {
}
