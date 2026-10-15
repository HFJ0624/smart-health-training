package com.hfj.fitagent.agent.model;

/**
 * 供应商调用失败的<b>语义分类</b>，与 HTTP 状态码解耦。
 *
 * <p>关键区分是 {@link #TRANSPORT_UNKNOWN} 与 {@link #TRANSPORT_BEFORE_SEND}：
 * 前者表示"请求已发出、结果未知"（官方可能已计费）→ 调用方必须保留不确定预占且不自动重发；
 * 后者只在能证明请求从未离开本机时（DNS 解析失败、连接被拒）才允许使用 → 预占可以释放。
 * 判不准时一律按前者，这是 B6.8/B10 的立场。
 */
public enum ProviderErrorKind {

    /** 401：凭据无效或被撤销，凭据需转 INVALID。 */
    CREDENTIAL_INVALID,
    /** 还没有可解密的凭据行（与"凭据被供应商拒绝"不同，处置是提示配置）。 */
    CREDENTIAL_MISSING,
    /** 402：供应商账户余额不足（BYOK 下这是用户自己的账户，不是应用预算）。 */
    INSUFFICIENT_BALANCE,
    /** 422：参数非法，允许一次有界修正重发（修正本身仍计入调用次数与费用）。 */
    INVALID_PARAMS,
    /** 400：请求构造错误，不重发。 */
    INVALID_REQUEST,
    /** 429：并发超限，不重发。 */
    RATE_LIMITED,
    /** 500 / 503 / 其它非 2xx：供应商不可用。 */
    PROVIDER_UNAVAILABLE,
    /** 200 但响应体不符合我们理解的形状：已发送，结果未知。 */
    RESPONSE_MALFORMED,
    /** 读超时、断连、写了一半：已发送，结果未知，<b>不重发</b>。 */
    TRANSPORT_UNKNOWN,
    /** 可证明未送达（DNS 失败 / 连接被拒 / 发送前就超时）。 */
    TRANSPORT_BEFORE_SEND,
    /** 上下文配对非法（孤儿 tool 消息、半个工具组）：不发出请求。 */
    PROTOCOL_MISMATCH,
    /** 配置层拒绝的组合（例如开启思考模式）：不发出请求。 */
    CONFIGURATION_REJECTED
}
