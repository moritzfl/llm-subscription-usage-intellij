package com.aiproxyoauth.server;

import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.logging.RequestLogger;
import com.aiproxyoauth.model.CodexInstructionsProvider;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.ApiKeyUtils;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

public class ProxyServer {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyServer.class);

    private final Javalin app;
    private final ServerConfig config;

    public ProxyServer(ServerConfig config, CodexHttpClient client, ModelResolver modelResolver,
                       UsageTracker usageTracker, ApiKeyStore apiKeyStore) {
        this.config = config;
        if (config.requiresApiKeyEnforcement() && !apiKeyStore.isEnforcing()) {
            throw new IllegalStateException(
                    "API key enforcement is required when binding to a non-loopback host: " + config.host()
            );
        }
        RequestLogger requestLogger = new RequestLogger(config.fullRequestLogging(), Path.of(config.requestLogDir()));
        CodexInstructionsProvider instructionsProvider = "latest-codex".equals(config.codexInstructionsMode())
                ? new CodexInstructionsProvider(
                        CodexInstructionsProvider.Mode.LATEST_CODEX,
                        config.instructions(),
                        Path.of(config.codexInstructionsCacheDir()),
                        Duration.ofMinutes(15),
                        client.getHttpClient()
                )
                : new CodexInstructionsProvider(config.instructions());

        this.app = Javalin.create(javalinConfig -> {
            javalinConfig.concurrency.useVirtualThreads = true;
            javalinConfig.startup.showJavalinBanner = false;

            if (config.allowAnyCors() || !config.allowedCorsOrigins().isEmpty()) {
                javalinConfig.bundledPlugins.enableCors(cors ->
                        cors.addRule(rule -> {
                            if (config.allowAnyCors()) {
                                rule.anyHost();
                            } else {
                                String first = config.allowedCorsOrigins().getFirst();
                                String[] rest = config.allowedCorsOrigins().stream()
                                        .skip(1)
                                        .toArray(String[]::new);
                                rule.allowHost(first, rest);
                            }
                        })
                );
            }

            javalinConfig.routes.before(ctx -> {
                ctx.attribute(AccessLogFields.REQUEST_ID, requestLogger.nextRequestId());
                ctx.attribute(AccessLogFields.START_NANOS, System.nanoTime());
            });
            javalinConfig.routes.after(ProxyServer::logAccessLine);

            // API key enforcement (opt-in: only when keys are configured)
            // Enforcement is evaluated once at startup. Keys can be hot-reloaded (which keys
            // are valid changes), but enforcement cannot be toggled on/off without a restart.
            if (apiKeyStore.isEnforcing()) {
                javalinConfig.routes.beforeMatched(ctx -> authenticateRequest(ctx, apiKeyStore));
            }

            // Routes
            javalinConfig.routes.get("/health", new HealthHandler());
            javalinConfig.routes.get("/v1/models", new ModelsHandler(modelResolver));
            javalinConfig.routes.get("/v1/model/info", new LiteLlmModelInfoHandler(modelResolver));
            javalinConfig.routes.get("/model/info", new LiteLlmModelInfoHandler(modelResolver));
            javalinConfig.routes.get("/v1/usage", new UsageHandler(usageTracker));
            javalinConfig.routes.post("/v1/responses",
                    new ResponsesHandler(client, config, usageTracker, requestLogger, instructionsProvider));
            javalinConfig.routes.post("/v1/chat/completions",
                    new ChatCompletionsHandler(client, config, usageTracker, requestLogger, instructionsProvider));

            // Missing upstream credentials surface as 401 so clients show the real cause
            // (e.g. "OpenAI login required") instead of a generic server error.
            javalinConfig.routes.exception(com.aiproxyoauth.auth.AuthRequiredException.class, (e, ctx) -> {
                LOG.warn("Rejected {} {}: {}", ctx.method(), ctx.path(), e.getMessage());
                JsonHelper.toErrorResponse(ctx, e.getMessage(), 401, "authentication_error");
            });

            // Global exception handler
            javalinConfig.routes.exception(Exception.class, (e, ctx) -> {
                LOG.error("Unhandled request failure for {} {}", ctx.method(), ctx.path(), e);
                JsonHelper.toErrorResponse(ctx, "Unexpected server error.", 500, "server_error");
            });

            // 404 handler
            javalinConfig.routes.error(404, ctx ->
                    JsonHelper.toErrorResponse(ctx, "Route not found.", 404, "not_found_error")
            );
        });
    }

    static void authenticateRequest(Context ctx, ApiKeyStore apiKeyStore) {
        if ("/health".equals(ctx.path())) return;
        if (isCorsPreflight(ctx)) return;
        String auth = ctx.header("Authorization");
        String key = (auth != null && auth.startsWith("Bearer "))
                ? auth.substring(7).strip() : null;
        if (key != null && key.equals(apiKeyStore.adminKey())) {
            ctx.attribute("isAdmin", true);
            ctx.attribute("adminKeyFingerprint", ApiKeyUtils.fingerprint(key));
            return;
        }
        String name = (key != null) ? apiKeyStore.lookup(key) : null;
        if (name == null) {
            // Reload-then-401: if the keys file changed since last load, reload it now so
            // the next request from this client succeeds. The current request gets a 401
            // which the client is expected to retry; this is intentional by design.
            apiKeyStore.reloadIfFileChanged();
            JsonHelper.toErrorResponse(ctx, "Invalid or missing API key.", 401, "auth_error");
            ctx.skipRemainingHandlers();
        } else {
            ctx.attribute("keyName", name);
            ctx.attribute("keyFingerprint", ApiKeyUtils.fingerprint(key));
        }
    }

    private static boolean isCorsPreflight(Context ctx) {
        return "OPTIONS".equalsIgnoreCase(String.valueOf(ctx.method()))
                && ctx.header("Origin") != null
                && ctx.header("Access-Control-Request-Method") != null;
    }

    private static void logAccessLine(Context ctx) {
        Long startNanos = ctx.attribute(AccessLogFields.START_NANOS);
        long durationMillis = startNanos == null ? 0L : Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        int responseStatus = ctx.statusCode();
        int status = accessLogStatus(ctx, responseStatus);
        System.out.printf("%s %s %s %d %dms id=%s mode=%s status=%d req_bytes=%s resp_bytes=%s%n",
                Instant.now(),
                ctx.method(),
                ctx.path(),
                responseStatus,
                durationMillis,
                valueOrDefault(ctx.attribute(AccessLogFields.REQUEST_ID), "?"),
                valueOrDefault(ctx.attribute(AccessLogFields.MODE), "internal"),
                status,
                getContentLength(ctx.header("Content-Length")),
                valueOrDefault(responseBytes(ctx), "0"));
    }

    private static int accessLogStatus(Context ctx, int responseStatus) {
        Integer upstreamStatus = ctx.attribute(AccessLogFields.UPSTREAM_STATUS);
        return upstreamStatus != null ? upstreamStatus : responseStatus;
    }

    private static String responseBytes(Context ctx) {
        Long recordedBytes = ctx.attribute(AccessLogFields.RESPONSE_BYTES);
        if (recordedBytes != null) {
            return String.valueOf(recordedBytes);
        }
        return getContentLength(ctx.res().getHeader("Content-Length"));
    }

    private static String getContentLength(String contentLength) {
        if (contentLength == null || contentLength.isBlank()) {
            return "0";
        }
        String trimmed = contentLength.strip();
        for (int i = 0; i < trimmed.length(); i++) {
            if (!Character.isDigit(trimmed.charAt(i))) {
                return "0";
            }
        }
        return trimmed;
    }

    private static String valueOrDefault(Object value, String defaulVal) {
        if (value == null) {
            return defaulVal;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? "-" : text;
    }

    public void start() {
        app.start(config.host(), config.port());
    }

    public void stop() {
        app.stop();
    }

    public Javalin getApp() {
        return app;
    }
}
