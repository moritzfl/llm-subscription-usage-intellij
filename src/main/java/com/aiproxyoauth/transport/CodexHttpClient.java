package com.aiproxyoauth.transport;

import com.aiproxyoauth.auth.CredentialsProvider;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.logging.RequestLogger;

import java.io.InputStream;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class CodexHttpClient {

    private final HttpClient httpClient;
    private final CredentialsProvider credentialsProvider;
    private final String baseUrl;
    private final RequestLogger requestLogger;

    public CodexHttpClient(ServerConfig config, CredentialsProvider credentialsProvider) {
        this(config, HttpClient.newBuilder()
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), credentialsProvider);
    }

    public CodexHttpClient(ServerConfig config, HttpClient httpClient, CredentialsProvider credentialsProvider) {
        this.credentialsProvider = credentialsProvider;
        this.baseUrl = config.baseUrl();
        this.httpClient = httpClient;
        this.requestLogger = new RequestLogger(config.fullRequestLogging(), Path.of(config.requestLogDir()));
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public HttpResponse<InputStream> request(String path, String method, String body,
                                              Map<String, String> extraHeaders) throws Exception {
        return request(path, method, body, extraHeaders, null, null);
    }

    public HttpResponse<InputStream> request(String path, String method, String body,
                                             Map<String, String> extraHeaders,
                                             String requestId,
                                             String promptCacheKey) throws Exception {
        String logRequestId = requestId != null ? requestId : requestLogger.nextRequestId();
        Map<String, String> authHeaders = credentialsProvider.getAuthHeaders();
        HttpResponse<InputStream> response = httpClient.send(
                buildRequest(path, method, body, extraHeaders, promptCacheKey, logRequestId, authHeaders),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 401 && refreshAfterUnauthorized(authHeaders)) {
            // Stream body of the rejected response is unused; discard it before retrying.
            drainQuietly(response);
            response = httpClient.send(
                    buildRequest(path, method, body, extraHeaders, promptCacheKey, logRequestId,
                            credentialsProvider.getAuthHeaders()),
                    HttpResponse.BodyHandlers.ofInputStream());
        }
        return withLoggedStreamingBody(response, logRequestId);
    }

    /**
     * With full request logging enabled, tees the streaming body so the bytes that
     * actually flowed are logged once the stream is consumed or closed. Without it,
     * the entry is written immediately with a placeholder (a no-op when disabled).
     */
    private HttpResponse<InputStream> withLoggedStreamingBody(HttpResponse<InputStream> response, String requestId) {
        if (!requestLogger.isEnabled()) {
            requestLogger.logUpstreamResponse(requestId, response.statusCode(), responseHeaders(response),
                    "[streaming body omitted]");
            return response;
        }
        InputStream tee = new LoggingTeeInputStream(response.body(), captured ->
                requestLogger.logUpstreamResponse(requestId, response.statusCode(), responseHeaders(response), captured));
        return new BodyReplacingHttpResponse(response, tee);
    }

    public HttpResponse<String> requestString(String path, String method, String body,
                                               Map<String, String> extraHeaders) throws Exception {
        String requestId = requestLogger.nextRequestId();
        Map<String, String> authHeaders = credentialsProvider.getAuthHeaders();
        HttpResponse<String> response = httpClient.send(
                buildRequest(path, method, body, extraHeaders, null, requestId, authHeaders),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 401 && refreshAfterUnauthorized(authHeaders)) {
            response = httpClient.send(
                    buildRequest(path, method, body, extraHeaders, null, requestId,
                            credentialsProvider.getAuthHeaders()),
                    HttpResponse.BodyHandlers.ofString());
        }
        requestLogger.logUpstreamResponse(requestId, response.statusCode(), responseHeaders(response), response.body());
        return response;
    }

    /**
     * Asks the credentials provider to refresh after an upstream 401, passing the exact
     * Authorization header the rejected request carried so the provider can deduplicate
     * concurrent refreshes. The refresh itself is owned by the provider (the IDE plugin
     * routes it to its secure-storage auth service); the proxy only triggers it. Returns
     * false when nothing was refreshed so the caller surfaces the original 401 instead of
     * retrying with the same doomed token.
     */
    private boolean refreshAfterUnauthorized(Map<String, String> sentAuthHeaders) {
        try {
            return credentialsProvider.refreshAfterUnauthorized(sentAuthHeaders.get("Authorization"));
        } catch (Exception e) {
            return false;
        }
    }

    private static void drainQuietly(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            if (body != null) {
                body.readAllBytes();
            }
        } catch (Exception ignored) {
        }
    }

    private HttpRequest buildRequest(String path, String method, String body,
                                     Map<String, String> extraHeaders,
                                     String promptCacheKey,
                                     String requestId,
                                     Map<String, String> authHeaders) throws Exception {
        String targetUrl = UrlResolver.resolveTargetUrl(path, baseUrl);
        Map<String, String> loggedHeaders = new LinkedHashMap<>();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl));

        for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
            loggedHeaders.put(entry.getKey(), entry.getValue());
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
                loggedHeaders.put(entry.getKey(), entry.getValue());
            }
        }
        if (promptCacheKey != null && !promptCacheKey.isBlank()) {
            builder.header("conversation_id", promptCacheKey);
            builder.header("session_id", promptCacheKey);
            loggedHeaders.put("conversation_id", promptCacheKey);
            loggedHeaders.put("session_id", promptCacheKey);
        }

        if (body != null && !body.isEmpty()) {
            builder.method(method != null ? method : "POST",
                    HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method != null ? method : "GET",
                    HttpRequest.BodyPublishers.noBody());
        }

        requestLogger.logUpstreamRequest(requestId, method != null ? method : "GET", path, loggedHeaders, body);
        return builder.build();
    }

    private static <T> Map<String, java.util.List<String>> responseHeaders(HttpResponse<T> response) {
        return response.headers() == null ? Map.of() : response.headers().map();
    }

    /**
     * Copies everything read through it into a bounded buffer and hands the captured
     * text to {@code onComplete} exactly once, at EOF or close, whichever comes first.
     */
    private static final class LoggingTeeInputStream extends java.io.FilterInputStream {
        private static final int MAX_CAPTURE_BYTES = 256 * 1024;

        private final java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        private final java.util.function.Consumer<String> onComplete;
        private boolean truncated;
        private boolean completed;

        private LoggingTeeInputStream(InputStream delegate, java.util.function.Consumer<String> onComplete) {
            super(delegate);
            this.onComplete = onComplete;
        }

        @Override
        public int read() throws java.io.IOException {
            int b = super.read();
            if (b == -1) {
                complete();
            } else {
                capture(new byte[]{(byte) b}, 0, 1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws java.io.IOException {
            int count = super.read(buffer, offset, length);
            if (count == -1) {
                complete();
            } else {
                capture(buffer, offset, count);
            }
            return count;
        }

        @Override
        public void close() throws java.io.IOException {
            complete();
            super.close();
        }

        private void capture(byte[] buffer, int offset, int count) {
            int room = MAX_CAPTURE_BYTES - captured.size();
            if (room <= 0) {
                truncated = true;
                return;
            }
            int toWrite = Math.min(room, count);
            captured.write(buffer, offset, toWrite);
            if (toWrite < count) {
                truncated = true;
            }
        }

        private void complete() {
            if (completed) {
                return;
            }
            completed = true;
            String body = captured.toString(java.nio.charset.StandardCharsets.UTF_8);
            onComplete.accept(truncated ? body + "\n[capture truncated]" : body);
        }
    }

    /** Delegates everything to the original response except the (tee-wrapped) body. */
    private record BodyReplacingHttpResponse(
            HttpResponse<InputStream> delegate,
            InputStream replacementBody
    ) implements HttpResponse<InputStream> {
        @Override
        public int statusCode() {
            return delegate.statusCode();
        }

        @Override
        public HttpRequest request() {
            return delegate.request();
        }

        @Override
        public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
            return delegate.previousResponse();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return delegate.headers();
        }

        @Override
        public InputStream body() {
            return replacementBody;
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return delegate.sslSession();
        }

        @Override
        public URI uri() {
            return delegate.uri();
        }

        @Override
        public HttpClient.Version version() {
            return delegate.version();
        }
    }
}
