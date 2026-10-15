package com.hfj.fitagent.modules.coach.vo;

/**
 * {@code POST /api/coach/conversations/{id}/runs} 的 202 响应（§11.1）。
 *
 * <p>{@code runId} / {@code conversationId} 是字符串（BigInt 对外一律字符串）；
 * {@code estimatedCost} 是<b>发送前</b>的保守预估，与 {@code /estimate} 同一条算法，
 * 不可核算时为 {@code null}。幂等重放返回的是同一个 run，包括同一个预估。
 */
public record RunSubmitVo(String runId,
                          String conversationId,
                          String status,
                          String estimatedCost,
                          String currency,
                          String idempotencyKey,
                          Boolean fakeModel) {
}
