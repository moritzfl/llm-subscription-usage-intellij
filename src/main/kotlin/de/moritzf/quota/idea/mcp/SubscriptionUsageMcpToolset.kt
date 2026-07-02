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
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.McpProviderToolStatus
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.supergrok.SuperGrokWebSearchClient
import de.moritzf.quota.zai.ZaiQuotaException
import de.moritzf.quota.zai.ZaiWebSearchClient
import java.nio.file.Path

/** Exposes subscription usage JSON and hosted subscription tools through IntelliJ's MCP server. */
class SubscriptionUsageMcpToolset(
    private val codexClient: CodexMcpClient = CodexMcpClient.createDefault(),
    private val kimiSearchClient: KimiWebSearchClient = KimiWebSearchClient.createDefault(),
    private val zaiSearchClient: ZaiWebSearchClient = ZaiWebSearchClient.createDefault(),
    private val miniMaxSearchClient: MiniMaxWebSearchClient = MiniMaxWebSearchClient.createDefault(),
    private val ollamaSearchClient: OllamaWebSearchClient = OllamaWebSearchClient.createDefault(),
    private val superGrokSearchClient: SuperGrokWebSearchClient = SuperGrokWebSearchClient.createDefault(),
) : McpToolset {
    @McpTool(name = "get_subscription_quota")
    @McpDescription(description = "Returns the latest subscription quota response JSON for the selected provider.")
    fun get_subscription_quota(
        @McpDescription(description = "Provider to query. Supported providers are derived from the shared provider enum.") provider: QuotaProviderType,
    ): String {
        return quotaResult(provider)
    }

    @McpTool(name = "subscription_tools_status")
    @McpDescription(description = "Returns per-provider status showing whether subscription quota access is configured and whether web search is available. Does not call provider APIs.")
    fun subscription_tools_status(): String {
        val listProviders = ListSearchProvider.entries.associateBy { it.providerType }

        val statuses = QuotaProviderType.entries.map { provider ->
            val quotaConfigured = isQuotaConfigured(provider)
            val searchType = when (provider) {
                QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK -> "answer"
                in listProviders -> "list"
                else -> null
            }
            val webSearchAvailable = searchType != null && isWebSearchConfigured(provider)
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
        return codexResult(
            codexClient.webSearch(
                query,
                searchContextSize,
                includeSources,
                externalWebAccess,
                allowedDomains,
                blockedDomains,
            ),
        )
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

    @McpTool(name = "codex_image_generation")
    @McpDescription(description = "Generates an image through Codex. If targetFile is provided, writes the image there and returns JSON metadata; otherwise returns the Codex JSON response including b64_json data.")
    fun codex_image_generation(
        @McpDescription(description = "Image prompt to send to Codex image generation.") prompt: String,
        @McpDescription(description = "Optional target image file path. Relative paths resolve against the open project root when available. Leave blank to return b64_json in the response. The extension selects any image format supported by the standard JDK ImageIO writers, such as png.") targetFile: String? = null,
    ): String {
        return codexResult(codexClient.imageGeneration(prompt, targetFile, projectBaseDirectory()))
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
        val authService = QuotaAuthService.getInstance()
        val token = authService.getAccessTokenBlocking(QuotaProviderType.SUPERGROK)
        if (token.isNullOrBlank()) {
            return errorResult("Grok login required. Log in from SuperGrok settings.")
        }
        fun runSearch(accessToken: String): String {
            return superGrokSearchClient.webSearch(accessToken, query, model, allowedDomains, blockedDomains, maxOutputTokens)
        }
        return try {
            runSearch(token)
        } catch (exception: SuperGrokQuotaException) {
            if (exception.statusCode == 401 || exception.statusCode == 403) {
                val refreshed = authService.forceRefreshBlocking(QuotaProviderType.SUPERGROK, token)
                if (!refreshed.isNullOrBlank()) {
                    return try {
                        runSearch(refreshed)
                    } catch (retryException: SuperGrokQuotaException) {
                        errorResult(retryException.message ?: "Grok web search failed.")
                    } catch (retryException: Exception) {
                        errorResult(retryException.message ?: "Grok web search failed.")
                    }
                }
            }
            errorResult(exception.message ?: "Grok web search failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Grok web search failed.")
        }
    }

    private fun kimiWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val store = KimiCredentialsStore.getInstance()
        val credentials = store.loadBlocking()
        if (credentials?.isUsable() != true) {
            return errorResult("Kimi login required. Log in from settings.")
        }
        return try {
            val result = kimiSearchClient.webSearch(credentials, query, limit, includeContent)
            if (result.credentials != credentials) {
                store.save(result.credentials)
            }
            result.body
        } catch (exception: KimiQuotaException) {
            errorResult(exception.message ?: "Kimi web search failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Kimi web search failed.")
        }
    }

    private fun zaiWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = ZaiApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        return try {
            zaiSearchClient.webSearch(apiKey, query, limit, includeContent)
        } catch (exception: ZaiQuotaException) {
            errorResult(exception.message ?: "Z.ai web search failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Z.ai web search failed.")
        }
    }

    private fun miniMaxWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = MiniMaxApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            return errorResult("MiniMax API key missing. Add a MiniMax API key in settings.")
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
        return errorResult(lastException?.message ?: "MiniMax web search failed.")
    }

    private fun ollamaWebSearch(query: String, limit: Int, includeContent: Boolean): String {
        val apiKey = OllamaApiKeyStore.getInstance().loadBlocking()
        if (apiKey.isNullOrBlank()) {
            return errorResult("Ollama API key missing. Add an Ollama API key in settings.")
        }
        return try {
            ollamaSearchClient.webSearch(apiKey, query, limit, includeContent)
        } catch (exception: OllamaQuotaException) {
            errorResult(exception.message ?: "Ollama web search failed.")
        } catch (exception: Exception) {
            errorResult(exception.message ?: "Ollama web search failed.")
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
            QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK ->
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

    private fun errorResult(errorMessage: String): String {
        return McpJson.error(errorMessage)
    }
}
