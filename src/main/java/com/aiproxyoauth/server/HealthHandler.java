package com.aiproxyoauth.server;

import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public class HealthHandler implements Handler {
    private static final String SERVICE_NAME = "AIProxyOauth";
    private static final String FALLBACK_VERSION = "1.1.0";

    private final LongSupplier nanoTime;
    private final long startedAtNanos;

    public HealthHandler() {
        this(System::nanoTime, System.nanoTime());
    }

    HealthHandler(LongSupplier nanoTime, long startedAtNanos) {
        this.nanoTime = nanoTime;
        this.startedAtNanos = startedAtNanos;
    }

    @Override
    public void handle(Context ctx) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("service", SERVICE_NAME);
        response.put("version", version());
        response.put("uptime_seconds", uptimeSeconds());

        JsonHelper.toJsonResponse(ctx, response);
    }

    private long uptimeSeconds() {
        long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedAtNanos);
        return elapsedNanos / 1_000_000_000L;
    }

    private static String version() {
        Package packageInfo = HealthHandler.class.getPackage();
        String implementationVersion = packageInfo != null ? packageInfo.getImplementationVersion() : null;
        if (implementationVersion == null || implementationVersion.isBlank()) {
            return FALLBACK_VERSION;
        }
        return implementationVersion;
    }
}
