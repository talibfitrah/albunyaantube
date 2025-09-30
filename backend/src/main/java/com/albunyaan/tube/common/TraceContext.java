package com.albunyaan.tube.common;

import org.slf4j.MDC;

public final class TraceContext {

    public static final String TRACE_ID_KEY = "traceId";

    private TraceContext() {}

    public static void set(String traceId) {
        if (traceId != null) {
            MDC.put(TRACE_ID_KEY, traceId);
        }
    }

    public static String get() {
        return MDC.get(TRACE_ID_KEY);
    }

    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }
}
