package com.aiproxyoauth.server;

import java.util.Locale;

public final class UpstreamErrorMapper {

    public record MappedUpstreamError(int statusCode, String body) {}

    public MappedUpstreamError map(int upstreamStatus, String upstreamBody) {
        int status = shouldMapUsageLimit(upstreamStatus, upstreamBody) ? 429 : upstreamStatus;
        return new MappedUpstreamError(status, JsonHelper.toUpstreamErrorBody(upstreamBody, status));
    }

    private boolean shouldMapUsageLimit(int upstreamStatus, String upstreamBody) {
        if (upstreamStatus != 404 || upstreamBody == null) {
            return false;
        }
        String body = upstreamBody.toLowerCase(Locale.ROOT);
        return body.contains("usage_limit_reached")
                || body.contains("usage_not_included")
                || body.contains("rate_limit_exceeded")
                || body.contains("usage limit");
    }
}
