package de.moritzf.quota.idea.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.diagnostic.Logger;
import de.moritzf.quota.dto.OAuthTokenResponseDto;
import de.moritzf.quota.idea.QuotaTokenUtil;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Performs OAuth token exchange and refresh requests against the configured token endpoint.
 */
public final class OAuthTokenClient {
    private static final Logger LOG = Logger.getInstance(OAuthTokenClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final OAuthClientConfig config;

    public OAuthTokenClient(@NotNull HttpClient httpClient, @NotNull OAuthClientConfig config) {
        this.httpClient = httpClient;
        this.config = config;
    }

    public @NotNull OAuthCredentials exchangeAuthorizationCode(@NotNull String code,
                                                               @NotNull String codeVerifier) throws IOException, InterruptedException {
        LOG.info("Exchanging authorization code for tokens");
        String body = OAuthUrlCodec.formEncode(Map.of(
                "grant_type", "authorization_code",
                "client_id", config.clientId(),
                "code", code,
                "redirect_uri", config.redirectUri(),
                "code_verifier", codeVerifier
        ));
        HttpResponse<String> response = postForm(body);
        if (!isSuccessful(response.statusCode())) {
            LOG.warn("Token exchange failed: " + response.statusCode());
            throw new IOException("Token exchange failed: " + response.statusCode() + " " + response.body());
        }
        OAuthTokenResponseDto tokenResponse = parseResponse(response.body());
        if (tokenResponse.refresh_token == null || tokenResponse.refresh_token.isBlank()) {
            throw new IOException("Token exchange did not return a refresh token");
        }
        OAuthCredentials credentials = createCredentials(tokenResponse);
        credentials.refreshToken = tokenResponse.refresh_token;
        return credentials;
    }

    public @NotNull OAuthCredentials refreshCredentials(@NotNull OAuthCredentials existing)
            throws IOException, InterruptedException {
        LOG.info("Refreshing OAuth token");
        String body = OAuthUrlCodec.formEncode(Map.of(
                "grant_type", "refresh_token",
                "client_id", config.clientId(),
                "refresh_token", existing.refreshToken
        ));
        HttpResponse<String> response = postForm(body);
        if (!isSuccessful(response.statusCode())) {
            LOG.warn("Token refresh failed: " + response.statusCode());
            throw new IOException("Token refresh failed: HTTP " + response.statusCode());
        }
        OAuthTokenResponseDto tokenResponse = parseResponse(response.body());
        OAuthCredentials credentials = createCredentials(tokenResponse);
        credentials.refreshToken = tokenResponse.refresh_token != null && !tokenResponse.refresh_token.isBlank()
                ? tokenResponse.refresh_token
                : existing.refreshToken;
        if (credentials.accountId == null) {
            credentials.accountId = existing.accountId;
        }
        return credentials;
    }

    private HttpResponse<String> postForm(String body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenEndpoint()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static @NotNull OAuthCredentials createCredentials(@NotNull OAuthTokenResponseDto tokenResponse) throws IOException {
        if (tokenResponse.access_token == null || tokenResponse.access_token.isBlank()) {
            throw new IOException("Token response did not return an access token");
        }
        OAuthCredentials credentials = new OAuthCredentials();
        credentials.accessToken = tokenResponse.access_token;
        credentials.expiresAt = System.currentTimeMillis() + tokenResponse.expires_in * 1000L;
        credentials.accountId = resolveAccountId(tokenResponse);
        return credentials;
    }

    private static OAuthTokenResponseDto parseResponse(String body) throws IOException {
        return MAPPER.readValue(body, OAuthTokenResponseDto.class);
    }

    private static String resolveAccountId(OAuthTokenResponseDto response) {
        String accountId = QuotaTokenUtil.extractChatGptAccountId(response.id_token);
        if (accountId == null) {
            accountId = QuotaTokenUtil.extractChatGptAccountId(response.access_token);
        }
        return accountId;
    }
}
