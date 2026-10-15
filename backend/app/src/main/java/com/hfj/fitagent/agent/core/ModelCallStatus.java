package com.hfj.fitagent.agent.core;

/**
 * 网关对一次远程调用的裁决。
 *
 * <p>之所以由网关而不是 Runner 给出：预算闸门、凭据状态、HTTP 结果、结算必须在一个不可拆开的调用点里发生，
 * 任何"Runner 自己判断要不要发"的分支都会留下绕过 BudgetGate 的路径（B10）。
 */
public enum ModelCallStatus {

    /** 远程调用成功且响应已解析。 */
    SUCCEEDED,
    /** 预算闸门拒绝发起（单次或当日额度不足）；本次未产生费用。 */
    BUDGET_STOPPED,
    /** 没有可用凭据。 */
    NO_CREDENTIAL,
    /** 401：凭据被供应商拒绝，凭据已转 INVALID。 */
    CREDENTIAL_INVALID,
    /** 402：供应商账户余额不足。 */
    INSUFFICIENT_BALANCE,
    /** 400 / 422（含一次有界修正后仍失败）：请求构造或参数问题。 */
    PROVIDER_UNAVAILABLE,
    /** 429：并发超限。 */
    RATE_LIMITED,
    /** 500 / 503：供应商不可用。 */
    PROVIDER_ERROR,
    /** 已发送但结果未知（超时、断连、响应体无法理解）：保留不确定预占、不重发。 */
    RESULT_UNKNOWN,
    /** 调用被取消。 */
    CANCELLED
}
