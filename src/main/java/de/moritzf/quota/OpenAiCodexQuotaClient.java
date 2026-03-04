package de.moritzf.quota;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.moritzf.quota.dto.RateLimitDto;
import de.moritzf.quota.dto.UsageResponseDto;
import de.moritzf.quota.dto.UsageWindowDto;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;

/**
 * HTTP client for fetching and parsing OpenAI Codex usage quota responses.
 */
public class OpenAiCodexQuotaClient {
    public static final URI DEFAULT_ENDPOINT = URI.create("https://chatgpt.com/backend-api/wham/usage");

    private final HttpClient httpClient;
    private final URI endpoint;
    private final ObjectMapper mapper;

    public OpenAiCodexQuotaClient() {
        this(HttpClient.newHttpClient(), DEFAULT_ENDPOINT);
    }

    public OpenAiCodexQuotaClient(HttpClient httpClient, URI endpoint) {
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient must not be null");
        }
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.mapper = new ObjectMapper();
    }

    public OpenAiCodexQuota fetchQuota(String accessToken, String accountId) throws IOException, InterruptedException {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be null or blank");
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .GET();

        if (accountId != null && !accountId.isBlank()) {
            requestBuilder.header("ChatGPT-Account-Id", accountId.trim());
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        String body = response.body();

        if (status < 200 || status >= 300) {
            throw new OpenAiCodexQuotaException("Usage request failed: " + status + " " + body, status);
        }

        return parseQuota(body, Instant.now());
    }

    OpenAiCodexQuota parseQuota(String json, Instant fetchedAt) throws IOException {
        UsageResponseDto dto = mapper.readValue(json, UsageResponseDto.class);
        RateLimitDto rateLimit = dto.rateLimit();

        UsageWindow primary = parseWindow(rateLimit != null ? rateLimit.primaryWindow() : null);
        UsageWindow secondary = parseWindow(rateLimit != null ? rateLimit.secondaryWindow() : null);

        String planType = textOrNull(dto.planType());
        String accountId = textOrNull(dto.accountId());
        String email = textOrNull(dto.email());
        Boolean allowed = rateLimit != null ? rateLimit.allowed() : null;
        Boolean limitReached = rateLimit != null ? rateLimit.limitReached() : null;

        OpenAiCodexQuota quota = new OpenAiCodexQuota();
        quota.setPrimary(primary);
        quota.setSecondary(secondary);
        quota.setPlanType(planType);
        quota.setAllowed(allowed);
        quota.setLimitReached(limitReached);
        quota.setFetchedAt(fetchedAt);
        quota.setRawJson(json);
        quota.setAccountId(accountId);
        quota.setEmail(email);

        if (primary == null && secondary == null) {
            throw new OpenAiCodexQuotaException("Usage response did not include rate_limit windows", 200);
        }

        return quota;
    }

    private UsageWindow parseWindow(UsageWindowDto node) {
        if (node == null) {
            return null;
        }

        Double usedPercentValue = node.usedPercent();
        if (usedPercentValue == null) {
            return null;
        }

        double usedPercent = clampPercent(usedPercentValue);
        Integer windowMinutes = null;
        Double windowSeconds = node.limitWindowSeconds();
        if (windowSeconds != null) {
            windowMinutes = Math.toIntExact(Math.round(windowSeconds / 60.0));
        }

        Instant resetsAt = null;
        Double resetAt = node.resetAt();
        if (resetAt != null) {
            long millis = Math.round(resetAt * 1000.0);
            resetsAt = Instant.ofEpochMilli(millis);
        }

        UsageWindow window = new UsageWindow();
        window.setUsedPercent(usedPercent);
        window.setWindowMinutes(windowMinutes);
        window.setResetsAt(resetsAt);
        return window;
    }

    private static double clampPercent(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 100.0) {
            return 100.0;
        }
        return value;
    }

    private static String textOrNull(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        return text;
    }
}
