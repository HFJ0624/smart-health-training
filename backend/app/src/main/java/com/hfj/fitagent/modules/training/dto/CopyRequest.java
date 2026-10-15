package com.hfj.fitagent.modules.training.dto;

/**
 * {@code POST /api/plans/{id}/copy} 的请求体。
 *
 * <p>同 {@link ReviseRequest}：复制产生的新计划组，其 {@code planGroupUuid}、{@code revisionNo=1}、
 * 标题后缀"（副本）"与 {@code source} 继承都由服务端决定，客户端不传任何字段
 * （docs/p1-api.md §7、docs/p1-plan-import.md §7）。
 */
public record CopyRequest() {
}
