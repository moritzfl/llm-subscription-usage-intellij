package com.aiproxyoauth.server;

import io.javalin.http.Context;

final class AccessLogFields {
    static final String REQUEST_ID = "requestId";
    static final String START_NANOS = "accessLogStartNanos";
    static final String MODE = "accessLogMode";
    static final String UPSTREAM_STATUS = "accessLogUpstreamStatus";
    static final String RESPONSE_BYTES = "accessLogResponseBytes";

    private AccessLogFields() {}

    static void mode(Context ctx, String mode) {
        ctx.attribute(MODE, mode);
    }

    static void upstreamStatus(Context ctx, int status) {
        ctx.attribute(UPSTREAM_STATUS, status);
    }

    static void responseBytes(Context ctx, long bytes) {
        ctx.attribute(RESPONSE_BYTES, Math.max(0L, bytes));
    }

    static void addResponseBytes(Context ctx, long bytes) {
        Long current = ctx.attribute(RESPONSE_BYTES);
        responseBytes(ctx, (current == null ? 0L : current) + Math.max(0L, bytes));
    }
}
