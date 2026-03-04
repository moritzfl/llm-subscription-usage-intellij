package de.moritzf.quota.idea;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import de.moritzf.quota.idea.auth.OAuthCredentials;
import de.moritzf.quota.idea.auth.OAuthCallbackResult;
import de.moritzf.quota.idea.auth.OAuthClientConfig;
import de.moritzf.quota.idea.auth.OAuthCredentialsStore;
import de.moritzf.quota.idea.auth.OAuthLoginFlow;
import de.moritzf.quota.idea.auth.OAuthTokenClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Coordinates OAuth login, credential storage, and token refresh for quota requests.
 */
@Service(Service.Level.APP)
public final class QuotaAuthService {
    private static final Logger LOG = Logger.getInstance(QuotaAuthService.class);
    private static final String SERVICE_NAME = "OpenAI Usage Quota OAuth";
    private static final String USER_NAME = "openai-oauth";

    private static final OAuthClientConfig OAUTH_CONFIG = OAuthClientConfig.openAiUsageQuotaDefaults();

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final OAuthTokenClient tokenClient = new OAuthTokenClient(httpClient, OAUTH_CONFIG);
    private final OAuthCredentialsStore credentialsStore = new OAuthCredentialsStore(SERVICE_NAME, USER_NAME);
    private final AtomicReference<OAuthCredentials> cachedCredentials = new AtomicReference<>();
    private final AtomicBoolean cacheLoading = new AtomicBoolean(false);
    private final AtomicBoolean authInProgress = new AtomicBoolean(false);
    private final AtomicReference<OAuthLoginFlow> pendingFlow = new AtomicReference<>();
    private final AtomicLong credentialClearCounter = new AtomicLong(0);

    public static QuotaAuthService getInstance() {
        return ApplicationManager.getApplication().getService(QuotaAuthService.class);
    }

    public QuotaAuthService() {
        refreshCacheAsync();
    }

    public void startLoginFlow(@NotNull Consumer<LoginResult> callback) {
        if (!authInProgress.compareAndSet(false, true)) {
            LOG.warn("Login requested while another login is already in progress");
            callback.accept(LoginResult.error("Login already in progress"));
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            LoginResult result;
            try {
                result = runLoginFlow();
            } catch (Exception exception) {
                LOG.warn("Login flow failed", exception);
                String message = exception.getMessage();
                if (message != null && message.toLowerCase().contains("address already in use")) {
                    message = "Port " + OAUTH_CONFIG.callbackPort() + " is already in use. Close the other app using it and try again.";
                }
                result = LoginResult.error(Objects.requireNonNullElse(message, "Login failed"));
            } finally {
                authInProgress.set(false);
            }
            callback.accept(result);
        });
    }

    public boolean isLoginInProgress() {
        return authInProgress.get();
    }

    public boolean abortLogin(@Nullable String reason) {
        OAuthLoginFlow flow = pendingFlow.getAndSet(null);
        if (flow == null) {
            return false;
        }
        authInProgress.set(false);
        String message = reason == null || reason.isBlank() ? "Login canceled" : reason;
        flow.cancel(message);
        LOG.info("Login flow aborted: " + message);
        return true;
    }

    public void clearCredentials() {
        credentialClearCounter.incrementAndGet();
        abortLogin("Logged out");
        cachedCredentials.set(null);
        credentialsStore.clear();
        LOG.info("Cleared stored OAuth credentials");
    }

    public boolean isLoggedIn() {
        OAuthCredentials credentials = cachedCredentials.get();
        if (credentials == null && !cacheLoading.get()) {
            refreshCacheAsync();
        }
        return credentials != null && credentials.accessToken != null && !credentials.accessToken.isBlank();
    }

    public @Nullable String getAccessTokenBlocking() {
        OAuthCredentials credentials = getCredentialsBlocking();
        if (credentials == null) {
            return null;
        }
        if (isExpired(credentials)) {
            credentials = refreshCredentialsBlocking(credentials);
        }
        return credentials != null ? credentials.accessToken : null;
    }

    public @Nullable String getAccountId() {
        OAuthCredentials credentials = cachedCredentials.get();
        if (credentials == null) {
            return null;
        }
        return credentials.accountId;
    }

    public void refreshCacheAsync() {
        if (!cacheLoading.compareAndSet(false, true)) {
            return;
        }
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                getCredentialsBlocking();
            } finally {
                cacheLoading.set(false);
            }
        });
    }

    private LoginResult runLoginFlow() throws Exception {
        LOG.info("Starting OAuth login flow");
        OAuthLoginFlow flow = OAuthLoginFlow.start(OAUTH_CONFIG);
        pendingFlow.set(flow);
        String callbackError = pingCallbackEndpoint();
        if (callbackError != null) {
            pendingFlow.compareAndSet(flow, null);
            flow.stopServerNow();
            return LoginResult.error(callbackError);
        }
        openAuthorizationUi(flow.getAuthorizationUrl());
        OAuthCallbackResult callback = flow.waitForCallback();
        pendingFlow.compareAndSet(flow, null);
        LOG.info("OAuth callback received; success=" + (callback.error() == null));
        if (callback.error() != null) {
            return LoginResult.error(callback.error());
        }
        if (callback.code() == null || callback.code().isBlank()) {
            return LoginResult.error("No authorization code received");
        }
        OAuthCredentials credentials = tokenClient.exchangeAuthorizationCode(callback.code(), flow.getCodeVerifier());
        saveCredentials(credentials);
        cachedCredentials.set(credentials);
        return LoginResult.success();
    }

    private @Nullable String pingCallbackEndpoint() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + OAUTH_CONFIG.callbackPort() + "/auth/ping"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? null
                    : "Callback test failed (HTTP " + response.statusCode() + ")";
        } catch (Exception exception) {
            return "Callback not reachable: " + exception.getClass().getSimpleName();
        }
    }

    private void openAuthorizationUi(String url) {
        LOG.info("Opening authorization UI: " + url);
        AppExecutorUtil.getAppExecutorService().execute(() -> BrowserUtil.browse(url));
    }

    private @Nullable OAuthCredentials getCredentialsBlocking() {
        long clearMarker = credentialClearCounter.get();
        OAuthCredentials credentials = credentialsStore.load();
        if (credentialClearCounter.get() != clearMarker) {
            cachedCredentials.set(null);
            return null;
        }
        if (credentials == null) {
            cachedCredentials.set(null);
            return null;
        }
        cachedCredentials.set(credentials);
        return credentials;
    }

    private void saveCredentials(@NotNull OAuthCredentials credentials) throws Exception {
        credentialsStore.save(credentials);
    }

    private OAuthCredentials refreshCredentialsBlocking(OAuthCredentials existing) {
        try {
            OAuthCredentials refreshed = tokenClient.refreshCredentials(existing);
            saveCredentials(refreshed);
            cachedCredentials.set(refreshed);
            return refreshed;
        } catch (Exception exception) {
            LOG.warn("Token refresh failed", exception);
            clearCredentials();
            return null;
        }
    }

    private static boolean isExpired(OAuthCredentials credentials) {
        return System.currentTimeMillis() >= credentials.expiresAt - TimeUnit.MINUTES.toMillis(5);
    }

    static Map<String, String> parseQuery(String query) {
        return OAuthLoginFlow.parseQuery(query);
    }

    static URI parseUri(String value) {
        return OAuthLoginFlow.parseUri(value, OAUTH_CONFIG.redirectUri());
    }

    /**
     * Result of a login flow execution.
     */
    public static final class LoginResult {
        public final boolean success;
        public final @Nullable String message;

        private LoginResult(boolean success, @Nullable String message) {
            this.success = success;
            this.message = message;
        }

        public static LoginResult success() {
            return new LoginResult(true, null);
        }

        public static LoginResult error(String message) {
            return new LoginResult(false, message);
        }
    }
}
