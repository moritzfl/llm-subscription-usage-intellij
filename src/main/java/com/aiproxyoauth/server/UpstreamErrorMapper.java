package com.aiproxyoauth.server;

import java.util.Locale;

public final class UpstreamErrorMapper {

    public record MappedUpstreamError(int statusCode, String body) {}

    public MappedUpstreamError map(int upstreamStatus, String upstreamBody) {
        boolean quotaExhausted = isQuotaExhausted(upstreamBody);
        boolean rateLimited = containsMarker(upstreamBody, "rate_limit_exceeded");
        // The Codex backend reports usage/rate limits with HTTP 404; clients expect 429.
        int status = upstreamStatus == 404 && (quotaExhausted || rateLimited) ? 429 : upstreamStatus;
        // "insufficient_quota" makes OpenAI-compatible clients (Junie included) stop and
        // surface the quota problem instead of retrying with backoff forever.
        String errorType = quotaExhausted ? "insufficient_quota" : null;
        return new MappedUpstreamError(status, JsonHelper.toUpstreamErrorBody(upstreamBody, status, errorType));
    }

    private boolean isQuotaExhausted(String upstreamBody) {
        return containsMarker(upstreamBody, "usage_limit_reached")
                || containsMarker(upstreamBody, "usage_not_included")
                || containsMarker(upstreamBody, "usage limit");
    }

    private boolean containsMarker(String upstreamBody, String marker) {
        return upstreamBody != null && upstreamBody.toLowerCase(Locale.ROOT).contains(marker);
    }
}
