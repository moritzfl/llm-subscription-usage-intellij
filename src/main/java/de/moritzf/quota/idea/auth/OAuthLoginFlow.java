package de.moritzf.quota.idea.auth;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs the browser-based OAuth login flow including local callback server handling.
 */
public final class OAuthLoginFlow {
    private static final Logger LOG = Logger.getInstance(OAuthLoginFlow.class);
    private static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CALLBACK_SHUTDOWN_DELAY = Duration.ofSeconds(3);

    private final OAuthClientConfig config;
    private final String codeVerifier;
    private final String state;
    private final String authorizationUrl;
    private final CompletableFuture<OAuthCallbackResult> callbackFuture = new CompletableFuture<>();

    private HttpServer server;
    private ExecutorService serverExecutor;

    private OAuthLoginFlow(@NotNull OAuthClientConfig config,
                           @NotNull String codeVerifier,
                           @NotNull String state,
                           @NotNull String authorizationUrl) {
        this.config = config;
        this.codeVerifier = codeVerifier;
        this.state = state;
        this.authorizationUrl = authorizationUrl;
    }

    public static @NotNull OAuthLoginFlow start(@NotNull OAuthClientConfig config) throws IOException {
        String verifier = generateCodeVerifier();
        String challenge = generateCodeChallenge(verifier);
        String state = generateState();
        String authorizationUrl = buildAuthorizationUrl(config, challenge, state);
        OAuthLoginFlow flow = new OAuthLoginFlow(config, verifier, state, authorizationUrl);
        flow.startServer();
        return flow;
    }

    public @NotNull String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public @NotNull String getCodeVerifier() {
        return codeVerifier;
    }

    public @NotNull OAuthCallbackResult waitForCallback() {
        try {
            OAuthCallbackResult callback = callbackFuture.get(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return callback == null ? new OAuthCallbackResult(null, "Authentication timed out") : callback;
        } catch (Exception exception) {
            return new OAuthCallbackResult(null, "Authentication timed out");
        } finally {
            scheduleStopServer();
        }
    }

    public void stopServerNow() {
        stopServer();
    }

    public void cancel(@NotNull String reason) {
        if (!callbackFuture.isDone()) {
            callbackFuture.complete(new OAuthCallbackResult(null, reason));
        }
        stopServer();
    }

    public static @NotNull Map<String, String> parseQuery(String query) {
        return OAuthUrlCodec.parseQuery(query);
    }

    public static @NotNull URI parseUri(String value, @NotNull String redirectUri) {
        return OAuthUrlCodec.parseCallbackUri(value, redirectUri);
    }

    private void startServer() throws IOException {
        try {
            server = HttpServer.create(new InetSocketAddress(config.callbackPort()), 0);
        } catch (IOException exception) {
            LOG.warn("Failed to bind OAuth callback server to " + config.redirectUri(), exception);
            throw exception;
        }
        server.createContext("/auth/ping", exchange -> {
            byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/auth/callback", this::handleCallback);
        serverExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "openai-oauth-callback");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.start();
        LOG.info("OAuth callback server started at " + config.redirectUri());
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        String responseText;
        try {
            if (exchange.getRemoteAddress() != null
                    && exchange.getRemoteAddress().getAddress() != null
                    && !exchange.getRemoteAddress().getAddress().isLoopbackAddress()) {
                LOG.warn("Rejected non-loopback callback from " + exchange.getRemoteAddress());
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            Map<String, String> params = OAuthUrlCodec.parseQuery(exchange.getRequestURI().getRawQuery());
            String error = params.get("error");
            if (error != null) {
                responseText = buildHtmlResponse("Authentication Failed", "Authentication failed: " + error, false);
                callbackFuture.complete(new OAuthCallbackResult(null, "OAuth error: " + error));
            } else {
                String code = params.get("code");
                String returnedState = params.get("state");
                if (code == null || returnedState == null) {
                    LOG.warn("Callback missing code/state");
                    responseText = buildHtmlResponse("Authentication Failed", "Missing code/state parameters.", false);
                    callbackFuture.complete(new OAuthCallbackResult(null, "Missing code or state"));
                } else if (!returnedState.equals(state)) {
                    LOG.warn("Callback state mismatch");
                    responseText = buildHtmlResponse("Authentication Failed", "State mismatch.", false);
                    callbackFuture.complete(new OAuthCallbackResult(null, "State mismatch"));
                } else {
                    LOG.info("Callback completed with authorization code");
                    responseText = buildHtmlResponse("Authentication Successful",
                            "You can close this window and return to the IDE.", true);
                    callbackFuture.complete(new OAuthCallbackResult(code, null));
                }
            }
        } catch (Exception exception) {
            LOG.warn("Callback handling failed", exception);
            responseText = buildHtmlResponse("Authentication Failed", "Authentication failed.", false);
            callbackFuture.complete(new OAuthCallbackResult(null, exception.getMessage()));
        }
        byte[] bytes = responseText.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOG.info("OAuth callback server stopped");
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
    }

    private void scheduleStopServer() {
        AppExecutorUtil.getAppExecutorService().execute(() -> {
            try {
                Thread.sleep(CALLBACK_SHUTDOWN_DELAY.toMillis());
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stopServer();
        });
    }

    private static String buildAuthorizationUrl(@NotNull OAuthClientConfig config,
                                                @NotNull String challenge,
                                                @NotNull String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", config.clientId());
        params.put("redirect_uri", config.redirectUri());
        params.put("scope", config.scopes());
        params.put("code_challenge", challenge);
        params.put("code_challenge_method", "S256");
        params.put("response_type", "code");
        params.put("state", state);
        params.put("codex_cli_simplified_flow", "true");
        params.put("originator", config.originator());
        return config.authorizationEndpoint() + "?" + OAuthUrlCodec.formEncode(params);
    }

    private static String generateCodeVerifier() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return base64Url(random);
    }

    private static String generateCodeChallenge(@NotNull String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return base64Url(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create code challenge", exception);
        }
    }

    private static String generateState() {
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        return base64Url(random);
    }

    private static String base64Url(byte[] value) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String buildHtmlResponse(String title, String message, boolean success) {
        @Language("html")
        String html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <title>%s</title>
                <style>
                  body {
                    font-family: Arial,serif;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    height: 100vh;
                    margin: 0;
                    background: %s;
                    color: white;
                  }
                  .container {
                    text-align: center;
                    padding: 2rem;
                  }
                  h1 { font-size: 1.6rem; margin-bottom: 0.5rem; }
                  p { opacity: 0.9; }
                </style>
                </head>
                <body>
                <div class="container">
                  <h1>%s</h1>
                  <p>%s</p>
                </div>
                </body>
                </html>
                """;
        String background = success ? "#0d8f6f" : "#b3282d";
        return html.formatted(title, background, title, message);
    }
}
