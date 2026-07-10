package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.ProjectManager
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeApiKeyStore
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuotaException
import de.moritzf.quota.kimi.KimiWebSearchClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxRegionPreference
import de.moritzf.quota.minimax.MiniMaxWebSearchClient
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.ollama.OllamaWebSearchClient
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.McpProviderToolStatus
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.supergrok.SuperGrokWebSearchClient
import de.moritzf.quota.zai.ZaiQuotaException
import de.moritzf.quota.zai.ZaiWebSearchClient
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Exposes subscription usage JSON and hosted subscription tools through IntelliJ's MCP server. */
class SubscriptionUsageMcpToolset(
    private val codexClient: CodexMcpClient = CodexMcpClient.createDefault(),
    private val kimiSearchClient: KimiWebSearchClient = KimiWebSearchClient.createDefault(),
    private val zaiSearchClient: ZaiWebSearchClient = ZaiWebSearchClient.createDefault(),
    private val miniMaxSearchClient: MiniMaxWebSearchClient = MiniMaxWebSearchClient.createDefault(),
    private val ollamaSearchClient: OllamaWebSearchClient = OllamaWebSearchClient.createDefault(),
    private val superGrokSearchClient: SuperGrokWebSearchClient = SuperGrokWebSearchClient.createDefault(),
    private val superGrokImagineClient: SuperGrokImagineClient = SuperGrokImagineClient.createDefault(),
) : McpToolset {
    @McpTool(name = "subscription_quota")
    @McpDescription(description = "Returns the latest subscription quota response JSON for the selected provider.")
    fun subscription_quota(
        @McpDescription(description = "Provider to query. Supported providers are derived from the shared provider enum.") provider: QuotaProviderType,
    ): String {
        return quotaResult(provider)
    }

    @McpTool(name = "subscription_tools_status")
    @McpDescription(description = "Returns per-provider status showing whether subscription quota access is configured and whether web search, image generation, and video generation are available. Does not call provider APIs.")
    fun subscription_tools_status(): String {
        val listProviders = ListSearchProvider.entries.associateBy { it.providerType }
        val imageProviders = ImageGenerationProvider.entries.associateBy { it.providerType }

        val statuses = QuotaProviderType.entries.map { provider ->
            val quotaConfigured = isQuotaConfigured(provider)
            val searchType = when (provider) {
                QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK -> "answer"
                in listProviders -> "list"
                else -> null
            }
            val webSearchAvailable = searchType != null && isWebSearchConfigured(provider)
            val imageGenerationAvailable = provider in imageProviders && isImageGenerationConfigured(provider)
            val videoGenerationAvailable = provider == QuotaProviderType.SUPERGROK && isImageGenerationConfigured(provider)
            val reason = if (searchType == null) {
                "Web search is not offered for this provider."
            } else if (!webSearchAvailable) {
                webSearchMissingReason(provider)
            } else {
                null
            }
            McpProviderToolStatus(
                provider = provider.displayName,
                quotaConfigured = quotaConfigured,
                webSearchAvailable = webSearchAvailable,
                webSearchType = searchType,
                imageGenerationAvailable = imageGenerationAvailable,
                videoGenerationAvailable = videoGenerationAvailable,
                reason = reason,
            )
        }
        return McpJson.toolsStatus(statuses)
    }

    @McpTool(name = "codex_web_search")
    @McpDescription(description = "Runs a Codex subscription-backed web search using the existing OpenAI login and returns the Codex JSON response.")
    fun codex_web_search(
        @McpDescription(description = "Search query to send to Codex web search.") query: String,
        @McpDescription(description = "Search context size: low, medium, or high. Higher values can improve detailed answers but may cost more and take longer.") searchContextSize: String = "medium",
        @McpDescription(description = "Whether to request the complete sources list from the web search call when available.") includeSources: Boolean = false,
        @McpDescription(description = "Whether the hosted search tool may fetch live web content. Set false for cached/indexed results only.") externalWebAccess: Boolean = true,
        @McpDescription(description = "Optional comma-separated domains to allow, for example openai.com,example.org. Leave blank for no allow filter.") allowedDomains: String? = null,
        @McpDescription(description = "Optional comma-separated domains to block, for example reddit.com,quora.com. Leave blank for no block filter.") blockedDomains: String? = null,
    ): String {
        val response = codexClient.webSearch(
            query,
            searchContextSize,
            includeSources,
            externalWebAccess,
            allowedDomains,
            blockedDomains,
        )
        return if (response.isError) searchError(extractErrorMessage(response.body)) else response.body
    }

    @McpTool(name = "supergrok_web_search")
    @McpDescription(description = "Runs a SuperGrok/xAI web search using the existing SuperGrok login and returns normalized JSON results.")
    fun supergrok_web_search(
        @McpDescription(description = "Search query to send to Grok web search.") query: String,
        @McpDescription(description = "xAI model to use for the Responses API web search request.") model: String = SuperGrokWebSearchClient.DEFAULT_MODEL,
        @McpDescription(description = "Optional comma-separated domains to allow, up to 5. Leave blank for no allow filter.") allowedDomains: String? = null,
        @McpDescription(description = "Optional comma-separated domains to exclude, up to 5. Leave blank for no exclude filter.") excludedDomains: String? = null,
        @McpDescription(description = "Maximum output tokens for the Grok answer. Values are clamped to Grok's safe local range.") maxOutputTokens: Int = SuperGrokWebSearchClient.DEFAULT_MAX_OUTPUT_TOKENS,
    ): String {
        return supergrokWebSearch(query, model, allowedDomains, excludedDomains, maxOutputTokens)
    }

    @McpTool(name = "subscription_web_search")
    @McpDescription(description = "Runs a result-list subscription-backed web search (Kimi, Z.ai, MiniMax, or Ollama) and returns the provider JSON response.")
    fun subscription_web_search(
        @McpDescription(description = "Provider to use. Supported providers are derived from the ListSearchProvider enum.") provider: ListSearchProvider = ListSearchProvider.KIMI,
        @McpDescription(description = "Search query.") query: String,
        @McpDescription(description = "Number of search results to request. Values are clamped to the provider's supported range.") limit: Int = 5,
        @McpDescription(description = "Whether to include full result content in addition to snippets. This can substantially increase response size.") includeContent: Boolean = false,
    ): String {
        return when (provider) {
            ListSearchProvider.KIMI -> kimiWebSearch(query, limit, includeContent)
            ListSearchProvider.ZAI -> zaiWebSearch(query, limit, includeContent)
            ListSearchProvider.MINIMAX -> miniMaxWebSearch(query, limit, includeContent)
            ListSearchProvider.OLLAMA -> ollamaWebSearch(query, limit, includeContent)
        }
    }

    @McpTool(name = "subscription_image_generation")
    @McpDescription(description = "Generates one image through a subscription-backed provider. Without targetFile, SuperGrok returns an image URL and OpenAI/Codex returns provider JSON (including b64). With targetFile, the image is written to disk so agents avoid large base64 payloads.")
    fun subscription_image_generation(
        @McpDescription(description = "Image prompt.") prompt: String,
        @McpDescription(description = "Provider to use: OPEN_AI (Codex) or SUPERGROK (xAI Imagine).") provider: ImageGenerationProvider = ImageGenerationProvider.OPEN_AI,
        @McpDescription(description = "Optional relative project path for the generated image (for example out/image.png). Leave blank to return provider JSON/URL instead of writing a file.") targetFile: String? = null,
    ): String {
        return when (provider) {
            ImageGenerationProvider.OPEN_AI ->
                codexResult(codexClient.imageGeneration(prompt, targetFile, projectBaseDirectory()))
            ImageGenerationProvider.SUPERGROK ->
                superGrokImageGeneration(prompt, targetFile)
        }
    }

    @McpTool(name = "supergrok_video_generation")
    @McpDescription(description = "Generates a video through SuperGrok/xAI Imagine using the existing SuperGrok login. By default waits/polls until completion and returns the final provider JSON.")
    fun supergrok_video_generation(
        @McpDescription(description = "Video prompt to send to Grok Imagine.") prompt: String,
        @McpDescription(description = "Imagine video model id, for example grok-imagine-video.") model: String = SuperGrokImagineClient.DEFAULT_VIDEO_MODEL,
        @McpDescription(description = "Requested video duration in seconds, clamped to the local safe range.") duration: Int = SuperGrokImagineClient.DEFAULT_VIDEO_DURATION_SECONDS,
        @McpDescription(description = "Optional public image URL or data URI used as the starting frame for image-to-video.") imageUrl: String? = null,
        @McpDescription(description = "When true, poll until the video finishes or times out. When false, return the initial request_id response immediately.") waitForCompletion: Boolean = true,
        @McpDescription(description = "Maximum seconds to wait when waitForCompletion is true.") pollTimeoutSeconds: Int = SuperGrokImagineClient.DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
    ): String {
        return superGrokVideoGeneration(prompt, model, duration, imageUrl, waitForCompletion, pollTimeoutSeconds)
    }

    private fun quotaResult(type: QuotaProviderType): String {
        val registration = UsageQuotaMcpRegistry.get(type)
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(type)

        val error = usageService.getLastError(type)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val payload = registration.json(usageService, type)
        if (payload.isNullOrBlank()) {
            return errorResult(registration.emptyMessage)
        }
        return payload
    }

    private fun codexResult(response: CodexMcpClient.CodexMcpResponse): String {
        return response.body
    }

    private fun supergrokWebSearch(
        query: String,
        model: String,
        allowedDomains: String?,
        blockedDomains: String?,
        maxOutputTokens: Int,
    ): String {
        return withSuperGrokAuth("Grok web search failed.") { accessToken ->
            superGrokSearchClient.webSearch(accessToken, query, model, allowedDomains, blockedDomains, maxOutputTokens)
        }
    }

    private fun superGrokImageGeneration(prompt: String, targetFile: String?): String {
        return withSuperGrokAuth("Grok image generation failed.") { accessToken ->
            superGrokImagineClient.generateImage(
                accessToken = accessToken,
                prompt = prompt,
                targetFile = targetFile,
                baseDirectory = projectBaseDirectory(),
            )
        }
    }

    private fun superGrokVideoGeneration(
        prompt: String,
        model: String,
        duration: Int,
        imageUrl: String?,
        waitForCompletion: Boolean,
        pollTimeoutSeconds: Int,
    ): String {
        return withSuperGrokAuth("Grok video generation failed.") { accessToken ->
            superGrokImagineClient.generateVideo(
                accessToken = accessToken,
                prompt = prompt,
                model = model,
                duration = duration,
                imageUrl = imageUrl,
                waitForCompletion = waitForCompletion,
                pollTimeoutSeconds = pollTimeoutSeconds,
            )
        }
    }

    private fun withSuperGrokAuth(failureLabel: String, block: (String) -> String): String {
        val authService = QuotaAuthService.getInstance()
        val token = authService.getAccessTokenBlocking(QuotaProviderType.SUPERGROK)
        if (token.isNullOrBlank()) {
            return searchError("Grok login required. Log in from SuperGrok settings.")
        }
        return try {
            block(token)
        } catch (exception: SuperGrokQuotaException) {
            if (exception.statusCode == 401 || exception.statusCode == 403) {
                val refreshed = authService.forceRefreshBlocking(QuotaProviderType.SUPERGROK, token)
                if (!refreshed.isNullOrBlank()) {
                    return try {
                        block(refreshed)
                    } catch (retryException: SuperGrokQuotaException) {
                        searchError(retryException.message ?: failureLabel)
                    } catch (retryException: Exception) {
                        searchError(retryException.message ?: failureLabel)
                    }
                }
            }
            searchError(exception.message ?: failureLabel)
        } catch (exception: Exception) {
            searchError(exception.message ?: failureLabel)
        }
    }

    private fun kimiWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val store = KimiCredentialsStore.getInstance()
        val credentials = store.loadBlocking()
        if (credentials?.isUsable() != true) {
            return searchError("Kimi login required. Log in from settings.")
        }
        return try {
            val result = kimiSearchClient.webSearch(credentials, query, limit, includeContent)
            if (result.credentials != credentials) {
                store.save(result.credentials)
            }
            result.body
        } catch (exception: KimiQuotaException) {
            searchError(exception.message ?: "Kimi web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Kimi web search failed.")
        }
    }

    private fun zaiWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = ZaiApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            return searchError("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiSearchClient.webSearch(apiKey, query, limit, includeContent)
        } catch (exception: ZaiQuotaException) {
            searchError(exception.message ?: "Z.ai web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Z.ai web search failed.")
        }
    }

    private fun miniMaxWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = MiniMaxApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            return searchError("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        var lastException: Exception? = null
        for (region in miniMaxSearchRegions()) {
            try {
                return miniMaxSearchClient.webSearch(apiKey, region, query, limit, includeContent)
            } catch (exception: MiniMaxQuotaException) {
                lastException = exception
            } catch (exception: Exception) {
                lastException = exception
            }
        }
        return searchError(lastException?.message ?: "MiniMax web search failed.")
    }

    private fun ollamaWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = OllamaApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            return searchError("Ollama API key missing. Add an Ollama API key in settings.")
        }
        return try {
            ollamaSearchClient.webSearch(apiKey, query, limit, includeContent)
        } catch (exception: OllamaQuotaException) {
            searchError(exception.message ?: "Ollama web search failed.")
        } catch (exception: Exception) {
            searchError(exception.message ?: "Ollama web search failed.")
        }
    }

    private fun projectBaseDirectory(): Path? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
            ?.basePath
            ?.let(Path::of)
    }

    private fun miniMaxSearchRegions(): List<MiniMaxRegion> {
        return when (runCatching { QuotaSettingsState.getInstance().miniMaxRegionPreference() }.getOrDefault(MiniMaxRegionPreference.AUTO)) {
            MiniMaxRegionPreference.GLOBAL -> listOf(MiniMaxRegion.GLOBAL)
            MiniMaxRegionPreference.CN -> listOf(MiniMaxRegion.CN)
            MiniMaxRegionPreference.AUTO -> listOf(MiniMaxRegion.GLOBAL, MiniMaxRegion.CN)
        }
    }

    private fun isQuotaConfigured(provider: QuotaProviderType): Boolean {
        return when (provider) {
            QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK, QuotaProviderType.CLAUDE ->
                !QuotaAuthService.getInstance().getAccessTokenBlocking(provider).isNullOrBlank()
            QuotaProviderType.GITHUB ->
                !GitHubCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            QuotaProviderType.KIMI ->
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            QuotaProviderType.OPEN_CODE ->
                !OpenCodeApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.ZAI ->
                !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.MINIMAX ->
                !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.OLLAMA ->
                !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.CURSOR ->
                !CursorCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
        }
    }

    private fun isWebSearchConfigured(provider: QuotaProviderType): Boolean {
        return when (provider) {
            QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK ->
                !QuotaAuthService.getInstance().getAccessTokenBlocking(provider).isNullOrBlank()
            QuotaProviderType.KIMI ->
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            QuotaProviderType.ZAI ->
                !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.MINIMAX ->
                !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.OLLAMA ->
                !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            else -> false
        }
    }

    private fun isImageGenerationConfigured(provider: QuotaProviderType): Boolean {
        return when (provider) {
            QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK ->
                !QuotaAuthService.getInstance().getAccessTokenBlocking(provider).isNullOrBlank()
            else -> false
        }
    }

    private fun webSearchMissingReason(provider: QuotaProviderType): String {
        return when (provider) {
            QuotaProviderType.OPEN_AI -> "OpenAI login required. Log in from settings."
            QuotaProviderType.SUPERGROK -> "Grok login required. Log in from SuperGrok settings."
            QuotaProviderType.KIMI -> "Kimi login required. Log in from settings."
            QuotaProviderType.ZAI -> "Z.ai API key missing. Add a Z.ai API key in settings."
            QuotaProviderType.MINIMAX -> "MiniMax API key missing. Add a MiniMax API key in settings."
            QuotaProviderType.OLLAMA -> "Ollama API key missing. Add an Ollama API key in settings."
            else -> "Web search is not offered for this provider."
        }
    }

    private fun searchError(message: String): String {
        val available = mutableListOf<String>()
        for (provider in QuotaProviderType.entries) {
            if (isWebSearchConfigured(provider)) {
                available.add(provider.displayName)
            }
        }
        val hint = if (available.isEmpty()) {
            " No search providers are currently configured."
        } else {
            " Currently configured search providers: ${available.joinToString(", ")}."
        }
        return errorResult(message + hint)
    }

    private fun extractErrorMessage(body: String): String {
        val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        val message = (root?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return message ?: body
    }

    private fun errorResult(errorMessage: String): String {
        return McpJson.error(errorMessage)
    }
}
