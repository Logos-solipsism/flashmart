package com.flashmart.common.trace;

import org.slf4j.MDC;

import java.util.UUID;

/** traceId 上下文：让一次请求跨 HTTP / MQ / 消费端的日志能用同一个 ID 串起来。 */
public final class TraceUtil {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";

    private TraceUtil() {
    }

    public static String start(String traceId) {
        String id = (traceId == null || traceId.isBlank())
                ? UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : traceId;
        MDC.put(MDC_KEY, id);
        return id;
    }

    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id == null ? "" : id;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
