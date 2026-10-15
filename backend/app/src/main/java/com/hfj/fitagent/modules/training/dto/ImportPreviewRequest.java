package com.hfj.fitagent.modules.training.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /api/plans/import/preview} 的请求体。
 *
 * <p>载体是<b>原始 JSON 文本</b>而不是已解析的对象：小程序侧两条入口
 * （从聊天会话选文件 / 粘贴文本）最终提交完全相同的文本，服务端才能对同一份字节做
 * 统一的体积与严格校验（docs/p1-plan-import.md §1）。
 *
 * <p>为什么用字符串套一层对象而不是 {@code text/plain}：鉴权、统一响应包与请求日志口径
 * 全部按 JSON 接口走，不额外开一条内容通道。
 *
 * @param content 待校验的计划 JSON 文本（UTF-8；字节上限在服务端按内容重新计数）
 */
public record ImportPreviewRequest(@NotBlank(message = "content 不能为空")
                                   String content) {
}
