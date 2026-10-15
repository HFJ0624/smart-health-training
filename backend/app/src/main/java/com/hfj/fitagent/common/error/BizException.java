package com.hfj.fitagent.common.error;

import org.springframework.http.ProblemDetail;

/**
 * 业务失败的唯一抛出方式。不用异常做常规流程控制。
 * payload 承载结构化错误（字段错误列表、微信数字码等），由 ApiResponse.data 透传给前端。
 */
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object payload;

    public BizException(ErrorCode errorCode) {
        this(errorCode, null, null);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BizException(ErrorCode errorCode, String message, Object payload) {
        super(message == null ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.payload = payload;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Object payload() {
        return payload;
    }

    /** 越权访问他人资源：语义上必须与"不存在"同形，避免泄露资源是否存在。 */
    public static BizException notFound() {
        return new BizException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    public static BizException versionConflict() {
        return new BizException(ErrorCode.VERSION_CONFLICT);
    }

    public static BizException invalidTransition(String detail) {
        return new BizException(ErrorCode.INVALID_STATE_TRANSITION, detail);
    }

    public ProblemDetail toProblemDetail() {
        ProblemDetail pd = ProblemDetail.forStatus(errorCode.status());
        pd.setTitle(errorCode.code());
        pd.setDetail(getMessage());
        return pd;
    }
}
