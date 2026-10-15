package com.hfj.fitagent.common.web;

import java.time.Instant;

/**
 * 全局唯一响应形状。前端只按 code 分支，不解析 message 文案。
 * traceId 用于让用户在失败截图中带上可排查的标识；不返回堆栈。
 */
public record ApiResponse<T>(String code, String message, T data, String traceId, Instant serverTime) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>("OK", null, data, TraceIds.current(), Instant.now());
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>("OK", null, data, TraceIds.current(), Instant.now());
    }

    public static ApiResponse<Object> error(String code, String message, Object payload, String traceId) {
        return new ApiResponse<>(code, message, payload, traceId, Instant.now());
    }
}
