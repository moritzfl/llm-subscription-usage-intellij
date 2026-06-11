package com.aiproxyoauth;

import com.aiproxyoauth.auth.AuthFileResolver;
import com.aiproxyoauth.auth.AuthLoader;
import com.aiproxyoauth.auth.AuthManager;
import com.aiproxyoauth.config.ServerConfig;
import com.aiproxyoauth.model.ModelResolver;
import com.aiproxyoauth.server.ApiKeyStore;
import com.aiproxyoauth.server.ProxyServer;
import com.aiproxyoauth.sse.SseParser;
import com.aiproxyoauth.sse.ServerSentEvent;
import com.aiproxyoauth.transport.CodexHttpClient;
import com.aiproxyoauth.usage.UsageTracker;
import com.aiproxyoauth.util.ApiKeyUtils;
import com.aiproxyoauth.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "AIProxyOauth",
        description = "Local HTTP proxy server exposing OpenAI-compatible endpoints via ChatGPT OAuth tokens.",
        mixinStandardHelpOptions = true,
        version = "AIProxyOauth 1.0.0"
)
public class AIProxyOauth implements Callable<Integer> {

    @Option(names = "--host", description = "Host interface to bind to. Default: 127.0.0.1")
    private String host;

    @Option(names = "--port", description = "Port to listen on. Default: 10531")
    private Integer port;

    @Option(names = "--models", description = "Comma-separated model ids to expose from /v1/models.")
    private String models;

    @Option(names = "--codex-version", description = "Codex API version to use for model discovery.")
    private String codexVersion;

    @Option(names = "--base-url", description = "Override the upstream Codex base URL.")
    private String baseUrl;

    @Option(names = "--oauth-client-id", description = "Override the OAuth client id used for refresh.")
    private String oauthClientId;

    @Option(names = "--oauth-token-url", description = "Override the OAuth token URL used for refresh.")
    private String oauthTokenUrl;

    @Option(names = "--oauth-file", description = "Path to the local auth.json file.")
    private String oauthFile;

    @Option(names = "--store", description = "Whether to ask upstream to store responses. Default: false")
    private boolean store;

    @Option(names = "--allow-any-cors", description = "Allow browser requests from any Origin. Default: false")
    private boolean allowAnyCors;

    @Option(names = "--cors-origin", split = ",", description = "Browser Origin allowed by CORS. Can be repeated or comma-separated.")
    private List<String> corsOrigins;

    @Option(names = "--log-requests", description = "Log full proxied request/response metadata to disk with sensitive headers redacted. Default: false")
    private boolean logRequests;

    @Option(names = "--request-log-dir", description = "Directory for --log-requests output. Default: ./logs/requests")
    private String requestLogDir;

    @Option(names = "--forward-prompt-cache-headers", description = "Forward prompt_cache_key as upstream conversation/session headers. Experimental. Default: false")
    private boolean forwardPromptCacheHeaders;

    @Option(names = "--responses-replay-cache", description = "Emulate previous_response_id/item_reference for store=false via an in-memory cache. Only needed for clients that chain responses server-side. Default: false")
    private boolean responsesReplayCache;

    @Option(names = "--codex-instructions", description = "Instruction source: configured or latest-codex. Default: configured")
    private String codexInstructionsMode;

    @Option(names = "--codex-instructions-cache-dir", description = "Directory for cached latest Codex instructions. Default: ./cache/codex-instructions")
    private String codexInstructionsCacheDir;

    @Option(names = "--api-key", description = "Comma-separated API keys clients must present.")
    private String apiKey;

    @Option(names = "--api-keys-file", description = "Path to file with one API key per line.")
    private String apiKeysFile;

    @Option(names = "--generate-key", arity = "0..1", fallbackValue = "",
            description = "Print a new random API key and exit. Optionally provide a name: --generate-key myapp")
    private String generateKey;

    @Option(names = "--admin-key", description = "Owner key that can see all users' stats at GET /v1/usage.")
    private String adminKey;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        if (generateKey != null) {
            return handleGenerateKey();
        }

        ServerConfig config = buildServerConfig();
        if (config.fullRequestLogging()) {
            System.err.println("WARNING: full request logging is enabled. Request/response bodies may contain prompts, "
                    + "tool outputs, file paths, and other sensitive data. Authorization and API key headers are "
                    + "redacted, but logs should still be protected.");
        }

        Map<String, String> inlineKeys = parseInlineKeys();
        if (config.adminKey() != null) inlineKeys.remove(config.adminKey());
        String explicitAdminKey = (adminKey != null && !adminKey.isBlank()) ? adminKey.strip() : null;
        ApiKeyStore apiKeyStore = new ApiKeyStore(inlineKeys, apiKeysFile, explicitAdminKey);
        if (apiKeysFile != null && !apiKeysFile.isBlank()) {
            apiKeyStore.reload();
        }
        apiKeyStore.startWatching();

        if (!checkAuthFileExists(config)) {
            return 1;
        }

        HttpClient authHttpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        AuthManager authManager = new AuthManager(config, authHttpClient);

        // Initial auth load to verify credentials
        AuthLoader.AuthResult authResult = authManager.ensureFresh();

        CodexHttpClient httpClient = new CodexHttpClient(config, authManager);
        ModelResolver modelResolver = new ModelResolver(httpClient, config.models(), config.codexVersion());

        // Discover models upfront
        List<String> availableModels = resolveAvailableModels(modelResolver);

        // Start server
        UsageTracker usageTracker = new UsageTracker();
        ProxyServer server = new ProxyServer(config, httpClient, modelResolver, usageTracker, apiKeyStore);
        server.start();

        StartupProbeResult startupProbe;
        try (HttpClient startupProbeClient = HttpClient.newHttpClient()) {
            startupProbe = verifyChatCompletionThroughProxy(
                    config,
                    availableModels,
                    apiKeyStore.isEnforcing() ? firstConfiguredApiKey(config) : null,
                    startupProbeClient
            );
        }

        printStartupBanner(config, availableModels, authResult.sourcePath(), apiKeyStore.isEnforcing(), startupProbe);
        setupShutdownHook(server, authHttpClient, apiKeyStore);

        // Keep main thread alive
        Thread.currentThread().join();
        return 0;
    }

    Integer handleGenerateKey() {
        String key = ApiKeyUtils.generateNewKey();
        spec.commandLine().getOut().println(generateKey.isEmpty() ? key : generateKey + ":" + key);
        return 0;
    }

    ServerConfig buildServerConfig() throws Exception {
        Map<String, String> apiKeyMap = parseApiKeyMap();
        String resolvedAdminKey = (adminKey != null && !adminKey.isBlank()) ? adminKey.strip() : null;

        // If no CLI admin key, look for an entry named "admin" in the keys map
        if (resolvedAdminKey == null) {
            String foundKey = null;
            for (Map.Entry<String, String> entry : apiKeyMap.entrySet()) {
                if ("admin".equalsIgnoreCase(entry.getValue())) {
                    foundKey = entry.getKey();
                    break;
                }
            }
            if (foundKey != null) {
                resolvedAdminKey = foundKey;
                apiKeyMap.remove(foundKey); // Remove from regular keys
            }
        }

        return new ServerConfig(
                host != null ? host : ServerConfig.DEFAULT_HOST,
                port != null ? port : ServerConfig.DEFAULT_PORT,
                parseModelList(),
                codexVersion,
                baseUrl != null ? baseUrl : ServerConfig.DEFAULT_BASE_URL,
                oauthClientId,
                oauthTokenUrl,
                oauthFile,
                ServerConfig.DEFAULT_INSTRUCTIONS,
                store,
                apiKeyMap,
                resolvedAdminKey,
                allowAnyCors,
                corsOrigins,
                logRequests,
                requestLogDir,
                forwardPromptCacheHeaders,
                codexInstructionsMode,
                codexInstructionsCacheDir,
                responsesReplayCache
        );
    }

    List<String> parseModelList() {
        if (models == null || models.isEmpty()) {
            return null;
        }
        List<String> modelList = Arrays.stream(models.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return modelList.isEmpty() ? null : modelList;
    }

    /** Returns only the keys from --api-key (not --api-keys-file). */
    Map<String, String> parseInlineKeys() {
        Map<String, String> map = new HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            Arrays.stream(apiKey.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, map));
        }
        return map;
    }

    Map<String, String> parseApiKeyMap() throws Exception {
        Map<String, String> apiKeyMap = new HashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            Arrays.stream(apiKey.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, apiKeyMap));
        }
        if (apiKeysFile != null && !apiKeysFile.isEmpty()) {
            Files.readAllLines(Path.of(apiKeysFile)).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .forEach(entry -> ApiKeyUtils.parseKeyEntry(entry, apiKeyMap));
        }
        return apiKeyMap;
    }

    boolean checkAuthFileExists(ServerConfig config) {
        String existingAuthFile = findExistingAuthFile(config.oauthFilePath());
        if (existingAuthFile == null) {
            List<String> candidates = AuthFileResolver.resolveCandidates(config.oauthFilePath());
            if (config.oauthFilePath() != null && !config.oauthFilePath().isEmpty()) {
                System.err.println("No auth file was found at " + config.oauthFilePath() + ".");
            } else {
                System.err.println("No auth file was found in the default search paths: "
                        + String.join(", ", candidates) + ".");
            }
            System.err.println("Run `codex login` and try again.");
            return false;
        }
        return true;
    }

    List<String> resolveAvailableModels(ModelResolver modelResolver) {
        try {
            return modelResolver.resolveModels();
        } catch (Exception e) {
            System.err.println("Warning: Could not discover models: " + e);
            return List.of();
        }
    }

    void printStartupBanner(ServerConfig config, List<String> availableModels) {
        printStartupBanner(
                config,
                availableModels,
                findExistingAuthFile(config.oauthFilePath()),
                isApiKeyEnforcementConfigured(config),
                null
        );
    }

    void printStartupBanner(
            ServerConfig config,
            List<String> availableModels,
            String authFilePath,
            boolean apiKeyEnforcement,
            StartupProbeResult startupProbe
    ) {
        java.io.PrintWriter out = spec.commandLine().getOut();
        String url = "http://" + config.host() + ":" + config.port() + "/v1";
        out.println();
        out.println("OpenAI OAuth Proxy Server started");
        out.println("  Endpoint: " + url);
        if (!availableModels.isEmpty()) {
            out.println("  Models:   " + String.join(", ", availableModels));
        }
        out.println("  Client API key enforcement: " + (apiKeyEnforcement ? "enabled" : "disabled"));
        out.println("  Network access: " + describeNetworkAccess(config.host()));
        out.println("  CORS: " + describeCors(config));
        if (authFilePath != null && !authFilePath.isBlank()) {
            out.println("  Auth file: " + authFilePath);
        }
        if (startupProbe != null) {
            out.println("  Startup check: " + (startupProbe.success()
                    ? "chat completion OK"
                    : "chat completion failed (" + startupProbe.message() + ")")
                    + " (model: " + startupProbe.model() + ")");
            if (startupProbe.responseText() != null && !startupProbe.responseText().isBlank()) {
                out.println("  Startup response: " + startupProbe.responseText());
            }
        }
        if (!config.apiKeys().isEmpty()) {
            String names = String.join(", ", config.apiKeys().values());
            out.println("  Keys:     " + config.apiKeys().size() + " key(s) configured (" + names + ")");
        }
        if (config.adminKey() != null) {
            out.println("  Admin:    key configured");
        }
        out.println();
    }

    record StartupProbeResult(boolean success, int statusCode, String message, String responseText, String model) {}

    StartupProbeResult verifyChatCompletionThroughProxy(
            ServerConfig config,
            List<String> availableModels,
            String apiKey,
            HttpClient httpClient
    ) {
        String model = selectStartupProbeModel(config, availableModels);
        String body = """
                {"model":"%s","messages":[{"role":"user","content":"Hello!"}],"stream":true}
                """.formatted(model);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(startupProbeUrl(config)))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            int status = response.statusCode();
            String responseText = status >= 200 && status < 300
                    ? extractStartupProbeResponseText(response.body())
                    : formatStartupProbeRawBody(response.body());
            if (status >= 200 && status < 300) {
                boolean hasModelResponse = hasActualStartupProbeResponse(responseText);
                return new StartupProbeResult(
                        hasModelResponse,
                        status,
                        hasModelResponse ? "HTTP " + status : "HTTP " + status + ", no model response text",
                        responseText,
                        model
                );
            }
            return new StartupProbeResult(false, status, "HTTP " + status, responseText, model);
        } catch (Exception e) {
            return new StartupProbeResult(false, 0, e.getClass().getSimpleName() + ": " + e.getMessage(), null, model);
        }
    }

    private static String extractStartupProbeResponseText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<empty response body>";
        }
        if (looksLikeSse(responseBody)) {
            return extractStreamingStartupProbeResponseText(responseBody);
        }
        try {
            JsonNode root = Json.MAPPER.readTree(responseBody);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return "<missing choices[0].message.content>";
            }
            JsonNode firstChoice = choices.get(0);
            JsonNode message = firstChoice != null ? firstChoice.get("message") : null;
            JsonNode content = message != null ? message.get("content") : null;
            if (content == null) {
                return "<missing choices[0].message.content>";
            }
            if (content.isNull()) {
                return "<null choices[0].message.content>";
            }
            if (content.isTextual()) {
                return formatStartupProbeText(content.asText());
            }
            return formatStartupProbeText(Json.MAPPER.writeValueAsString(content));
        } catch (Exception e) {
            return "<unparseable response body: " + formatStartupProbeText(responseBody) + ">";
        }
    }

    private static String extractStreamingStartupProbeResponseText(String responseBody) {
        StringBuilder text = new StringBuilder();
        boolean sawNullContent = false;
        try {
            ByteArrayInputStream input = new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8));
            for (ServerSentEvent event : SseParser.parse(input)) {
                String data = event.data();
                if (data == null || data.isBlank()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }

                JsonNode root = Json.MAPPER.readTree(data);
                JsonNode choices = root.get("choices");
                if (choices == null || !choices.isArray()) {
                    continue;
                }
                for (JsonNode choice : choices) {
                    JsonNode delta = choice.get("delta");
                    JsonNode content = delta != null ? delta.get("content") : null;
                    if (content == null) {
                        continue;
                    }
                    if (content.isNull()) {
                        sawNullContent = true;
                    } else if (content.isTextual()) {
                        text.append(content.asText());
                    } else {
                        text.append(Json.MAPPER.writeValueAsString(content));
                    }
                }
            }
        } catch (Exception e) {
            return "<unparseable streaming response body: " + formatStartupProbeText(responseBody) + ">";
        }

        if (!text.isEmpty()) {
            return formatStartupProbeText(text.toString());
        }
        return sawNullContent
                ? "<null streaming choices[].delta.content>"
                : "<missing streaming choices[].delta.content>";
    }

    private static boolean looksLikeSse(String responseBody) {
        String trimmed = responseBody.stripLeading();
        return trimmed.startsWith("data:") || trimmed.startsWith("event:");
    }

    private static boolean hasActualStartupProbeResponse(String responseText) {
        return responseText != null && !responseText.isBlank() && !responseText.startsWith("<");
    }

    private static String formatStartupProbeRawBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "<empty response body>";
        }
        return formatStartupProbeText(responseBody);
    }

    private static String formatStartupProbeText(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String selectStartupProbeModel(ServerConfig config, List<String> availableModels) {
        if (availableModels != null && !availableModels.isEmpty()) {
            return availableModels.getFirst();
        }
        if (config.models() != null && !config.models().isEmpty()) {
            return config.models().getFirst();
        }
        return ServerConfig.DEFAULT_MODEL;
    }

    private static String startupProbeUrl(ServerConfig config) {
        String host = clientHostForBindHost(config.host());
        return "http://" + hostForUri(host) + ":" + config.port() + "/v1/chat/completions";
    }

    private static String clientHostForBindHost(String host) {
        if (host == null || host.isBlank()) {
            return ServerConfig.DEFAULT_HOST;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if ("0.0.0.0".equals(normalized) || "::".equals(normalized) || "0:0:0:0:0:0:0:0".equals(normalized)) {
            return ServerConfig.DEFAULT_HOST;
        }
        return host.strip();
    }

    private static String hostForUri(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static String firstConfiguredApiKey(ServerConfig config) {
        if (config.adminKey() != null) {
            return config.adminKey();
        }
        return config.apiKeys().keySet().stream().findFirst().orElse(null);
    }

    static String describeNetworkAccess(String host) {
        return isLocalOnlyHost(host) ? "Local access only" : "Full network access";
    }

    static String describeCors(ServerConfig config) {
        if (config.allowAnyCors()) {
            return "any origin";
        }
        if (!config.allowedCorsOrigins().isEmpty()) {
            return String.join(", ", config.allowedCorsOrigins());
        }
        return "disabled";
    }

    private static boolean isLocalOnlyHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || normalized.startsWith("127.");
    }

    private static boolean isApiKeyEnforcementConfigured(ServerConfig config) {
        return !config.apiKeys().isEmpty() || config.adminKey() != null;
    }

    void setupShutdownHook(ProxyServer server, HttpClient authHttpClient, ApiKeyStore apiKeyStore) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Shutting down...");
            server.stop();
            authHttpClient.close();
            apiKeyStore.stopWatching();
        }, "shutdown-hook"));
    }

    static String findExistingAuthFile(String authFilePath) {
        for (String candidate : AuthFileResolver.resolveCandidates(authFilePath)) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AIProxyOauth()).execute(args);
        System.exit(exitCode);
    }
}
