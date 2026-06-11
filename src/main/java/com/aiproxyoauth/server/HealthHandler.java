package com.aiproxyoauth.server;

import com.aiproxyoauth.util.ProxyVersion;
import io.javalin.http.Context;
import io.javalin.http.Handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public class HealthHandler implements Handler {
    private static final String SERVICE_NAME = "AIProxyOauth";

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
        response.put("version", ProxyVersion.get());
        response.put("uptime_seconds", uptimeSeconds());

        JsonHelper.toJsonResponse(ctx, response);
    }

    private long uptimeSeconds() {
        long elapsedNanos = Math.max(0L, nanoTime.getAsLong() - startedAtNanos);
        return elapsedNanos / 1_000_000_000L;
    }
}
