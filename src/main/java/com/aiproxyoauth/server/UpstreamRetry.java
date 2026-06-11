package com.aiproxyoauth.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpResponse;

/**
 * Honors LiteLLM's {@code x-litellm-num-retries} request header by retrying the
 * upstream call on transient failures (5xx responses and I/O errors).
 */
final class UpstreamRetry {

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BACKOFF_MILLIS = 250;

    interface UpstreamCall {
        HttpResponse<InputStream> send() throws Exception;
    }

    private UpstreamRetry() {}

    static HttpResponse<InputStream> withRetries(String numRetriesHeader, UpstreamCall call) throws Exception {
        int retries = parseRetries(numRetriesHeader);
        int attempt = 0;
        while (true) {
            try {
                HttpResponse<InputStream> response = call.send();
                if (attempt >= retries || response.statusCode() < 500) {
                    return response;
                }
                closeQuietly(response);
            } catch (IOException e) {
                if (attempt >= retries) {
                    throw e;
                }
            }
            attempt++;
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt);
        }
    }

    private static int parseRetries(String header) {
        if (header == null || header.isBlank()) {
            return 0;
        }
        try {
            return Math.clamp(Integer.parseInt(header.strip()), 0, MAX_RETRIES);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            if (body != null) {
                body.readAllBytes();
            }
        } catch (Exception ignored) {
        }
    }
}
