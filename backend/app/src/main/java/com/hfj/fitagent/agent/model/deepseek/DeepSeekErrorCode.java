package com.hfj.fitagent.agent.model.deepseek;

/**
 * DeepSeek 适配器自己的稳定错误码（映射依据：docs/agent-v1-protocol.md §9 的表）。
 *
 * <p>供应商 4xx 响应体的 JSON 结构官方没有给出 schema（protocol §9 "未能核实"），
 * 因此这里<b>只以 HTTP 状态码 + 我方自己的码</b>作为可信信息，不解析、不依赖对方 body 的字段名，
 * 也不把对方文案透传给客户端。
 */
public final class DeepSeekErrorCode {

    /** 200 且可解析。 */
    public static final String OK = "OK";
    /** 401：凭据无效/已撤销 → ErrorCode.CREDENTIAL_INVALID，凭据行转 INVALID，run 转 WAITING_CREDENTIAL。 */
    public static final String CREDENTIAL_INVALID = "CREDENTIAL_INVALID";
    /** 没有可用凭据行（与"被供应商拒绝"不同）。 */
    public static final String CREDENTIAL_MISSING = "CREDENTIAL_MISSING";
    /** 402：供应商账户余额不足（BYOK 下这是用户自己的账户问题，不是应用预算）。 */
    public static final String INSUFFICIENT_BALANCE = "INSUFFICIENT_BALANCE";
    /** 422：参数非法 → 允许一次有界修正重发。 */
    public static final String INVALID_PARAMS = "INVALID_PARAMS";
    /** 400：请求构造错误 → 不重发。 */
    public static final String INVALID_REQUEST = "INVALID_REQUEST";
    /** 429：并发数超限（账户级：flash 2500 / v4-pro 500）→ 不重发。 */
    public static final String RATE_LIMITED = "RATE_LIMITED";
    /** 500 / 503：供应商不可用。 */
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    /** 已发出但超时/断连/响应体无法理解：结果未知，不重发，预占转 UNCERTAIN。 */
    public static final String RESPONSE_UNKNOWN = "RESPONSE_UNKNOWN";
    /** 可证明未送达（DNS 失败/连接被拒/发送前超时）：允许释放预占。 */
    public static final String NOT_SENT = "NOT_SENT";
    /** 200 但结构不符合我们已核实的形状（缺 choices 等）。 */
    public static final String RESPONSE_MALFORMED = "RESPONSE_MALFORMED";
    /** 上下文配对非法，请求没有发出。 */
    public static final String PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH";
    /** 思考模式被配置或请求打开：本轮禁止（protocol §5）。 */
    public static final String THINKING_MODE_FORBIDDEN = "THINKING_MODE_FORBIDDEN";
    /** 输出上限超过目录/官方硬顶：请求没有发出。 */
    public static final String MAX_TOKENS_OUT_OF_RANGE = "MAX_TOKENS_OUT_OF_RANGE";
    /** 模型代码是已停用名称（deepseek-chat / deepseek-reasoner，2026-07-24 停用）。 */
    public static final String MODEL_CODE_RETIRED = "MODEL_CODE_RETIRED";
    /** 传输超时（受 run 剩余时间约束的那一次）。 */
    public static final String TIMEOUT = "TIMEOUT";

    private DeepSeekErrorCode() {
    }
}
