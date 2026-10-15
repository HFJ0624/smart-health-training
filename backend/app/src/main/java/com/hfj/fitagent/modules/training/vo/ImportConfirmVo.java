package com.hfj.fitagent.modules.training.vo;

/**
 * 导入确认（{@code POST /api/plans/import}）的结果（docs/p1-plan-import.md §10）。
 *
 * <p>只回"这份草稿的身份"，不回整个聚合：页面拿到 {@code planId} 就跳草稿详情，
 * 详情接口才是聚合的读取口。少一份重复结构就少一处可能与真实数据不一致的地方。
 *
 * <p>{@code status} 恒为 {@code DRAFT}、{@code revisionNo} 恒为 1——导入永远不可能直接生效，
 * 用户必须显式点"确认生效"（§7）。把这两个值回出来是为了让页面能自证这一点，而不是靠前端写死。
 *
 * @param idempotencyKey   本次实际使用的幂等键；请求没带时这里是服务端生成的新值，
 *                         客户端应存下来用于重试（§10）
 * @param idempotentReplay true 表示命中原键的首次结果，<b>没有</b>再写任何行
 */
public record ImportConfirmVo(String planId,
                              Integer revisionNo,
                              String status,
                              String idempotencyKey,
                              boolean idempotentReplay) {
}
