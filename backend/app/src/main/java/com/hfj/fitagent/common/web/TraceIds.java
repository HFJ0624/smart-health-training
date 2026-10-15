package com.hfj.fitagent.common.web;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * traceId 由入口过滤器写入 MDC，响应包装与日志共用同一个值。
 */
public final class TraceIds {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceIds() {
    }

    public static String current() {
        String v = MDC.get(MDC_KEY);
        return v == null ? "unknown" : v;
    }

    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
