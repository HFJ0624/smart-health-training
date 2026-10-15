package com.hfj.fitagent.agent.core;

import com.hfj.fitagent.agent.model.ModelResponse;

/**
 * 网关返回的调用结果。
 *
 * @param requestSent 是否可能已经把请求送达供应商。已发送的失败即使没有结果也可能已计费，
 *                    Runner 据此把 {@code costUncertain} 置真（B10：未知费用不归零、不重发）
 * @param errorCode   稳定机器码（如 CREDENTIAL_INVALID / RATE_LIMITED / RESPONSE_UNKNOWN），前端按它分支
 */
public record ModelCallOutcome(ModelCallStatus status,
                               ModelResponse response,
                               String errorCode,
                               String safeDetail,
                               boolean requestSent) {

    public ModelCallOutcome {
        if (status == null) {
            throw new IllegalArgumentException("ModelCallOutcome.status 不能为空");
        }
        if (status == ModelCallStatus.SUCCEEDED && response == null) {
            throw new IllegalArgumentException("SUCCEEDED 必须携带已解析的响应");
        }
    }

    public static ModelCallOutcome succeeded(ModelResponse response) {
        return new ModelCallOutcome(ModelCallStatus.SUCCEEDED, response, null, null, true);
    }

    public static ModelCallOutcome stopped(ModelCallStatus status, String errorCode, String safeDetail) {
        return new ModelCallOutcome(status, null, errorCode, safeDetail, false);
    }

    public static ModelCallOutcome failed(ModelCallStatus status, String errorCode, String safeDetail,
                                         boolean requestSent) {
        return new ModelCallOutcome(status, null, errorCode, safeDetail, requestSent);
    }

    public boolean succeeded() {
        return status == ModelCallStatus.SUCCEEDED;
    }

    /** 用量未知（没有响应，或响应里所有分项都是 null）。 */
    public boolean usageUnknown() {
        return response == null || !response.usage().hasAnyKnown();
    }

    @Override
    public String toString() {
        return "ModelCallOutcome[status=" + status + ", code=" + errorCode + ", requestSent=" + requestSent + "]";
    }
}
