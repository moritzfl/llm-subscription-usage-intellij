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
        requestLogger.logUpstreamResponse(logRequestId, response.statusCode(), responseHeaders(response),
                "[streaming body omitted]");
        return response;
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
}
