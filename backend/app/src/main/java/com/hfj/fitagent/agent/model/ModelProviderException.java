package com.hfj.fitagent.agent.model;

/**
 * 供应商调用失败的统一异常。
 *
 * <p>三条纪律：
 * <ol>
 *   <li>异常文本<b>永远</b>不含 API Key、Authorization 头、请求体原文。JDK HttpClient 的异常消息可能带上完整
 *       URL，因此适配器只记录我方自己的 code，不透传供应商 body 字段（protocol §9 "未能核实" 4xx 响应体结构）。</li>
 *   <li>{@code requestSent} 由适配器判定，调用方据此决定"释放预占"还是"保留不确定预占"。</li>
 *   <li>不用异常做常规流程控制：它只表示"这次远程调用没有可信结果"。</li>
 * </ol>
 */
public class ModelProviderException extends RuntimeException {

    private final ProviderErrorKind kind;
    private final String providerErrorCode;
    private final boolean requestSent;
    private final Integer httpStatus;

    public ModelProviderException(ProviderErrorKind kind, String providerErrorCode, String safeDetail,
                                  boolean requestSent, Integer httpStatus) {
        super(buildMessage(kind, providerErrorCode, safeDetail, httpStatus));
        this.kind = kind;
        this.providerErrorCode = providerErrorCode;
        this.requestSent = requestSent;
        this.httpStatus = httpStatus;
    }

    /** 未发出请求的本地失败（配置/协议非法），语义上等价于"确定未送达"。 */
    public ModelProviderException(ProviderErrorKind kind, String providerErrorCode, String safeDetail) {
        this(kind, providerErrorCode, safeDetail, false, null);
    }

    public ProviderErrorKind kind() {
        return kind;
    }

    /** 我方自己的稳定码（见 DeepSeekErrorCode），前端按它分支，不解析 message。 */
    public String providerErrorCode() {
        return providerErrorCode;
    }

    public boolean requestSent() {
        return requestSent;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    /** 已发送且结果未知——结算必须按"不确定预占保留"处理。 */
    public boolean resultUnknown() {
        return requestSent && (kind == ProviderErrorKind.TRANSPORT_UNKNOWN
                || kind == ProviderErrorKind.RESPONSE_MALFORMED
                || kind == ProviderErrorKind.PROVIDER_UNAVAILABLE);
    }

    private static String buildMessage(ProviderErrorKind kind, String code, String detail, Integer status) {
        return "模型调用失败 kind=" + kind + " code=" + code
                + (status == null ? "" : " httpStatus=" + status)
                + (detail == null || detail.isBlank() ? "" : " detail=" + detail);
    }
}
