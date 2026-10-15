package com.hfj.fitagent.modules.training.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/plans/import}（确认保存）的请求体。
 *
 * <p>与 {@link ImportPreviewRequest} 同样是<b>原始 JSON 文本</b>：确认接口必须重新完整校验，
 * 不能把 preview 的规范化结果当输入（preview 与 confirm 之间字典可能被维护、幂等键可能已被用，
 * 见 docs/p1-plan-import.md §10）。若只回传"已规范化的对象"，等于让客户端决定服务端写什么，
 * 那份数据就成了不可信输入——文本才是唯一的原始真相。
 *
 * <p>幂等键走请求头 {@code Idempotency-Key}，不在请求体里（服务端字段不接受客户端注入）。
 *
 * @param content 与 preview 完全相同的计划 JSON 文本
 */
public record ImportConfirmRequest(@NotBlank(message = "content 不能为空")
                                   String content) {
}
