package com.hfj.fitagent.modules.training.dto;

/**
 * {@code POST /api/plans/{id}/revise} 的请求体。
 *
 * <p>本阶段这个动作没有任何可调参数：新版本的 {@code planGroupUuid}（沿用来源组）、
 * {@code revisionNo}（组内 max+1）、{@code basePlanId}（指向来源版本）、{@code goalSnapshot}
 * 全部由服务端生成，客户端一个都不能指定（docs/p1-plan-import.md §7 的服务端字段黑名单）。
 * 因此这里刻意是一个空记录：请求体里出现任何键都会被全局
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 挡掉，不存在"多余字段被静默忽略"的空间。
 *
 * <p>控制器用 {@code @RequestBody(required = false)} 绑定，允许不传体或传 {@code {}}。
 */
public record ReviseRequest() {
}
