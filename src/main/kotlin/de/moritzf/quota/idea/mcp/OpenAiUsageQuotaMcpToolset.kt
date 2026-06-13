package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.project.ProjectManager
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.kimi.KimiQuotaException
import de.moritzf.quota.kimi.KimiWebSearchClient
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path

/**
 * Exposes the latest subscription usage JSON through IntelliJ's MCP server.
 * Each tool must stay a discrete annotated method; the bodies delegate to [quotaResult].
 */
class OpenAiUsageQuotaMcpToolset(
    private val codexClient: CodexMcpClient = CodexMcpClient.createDefault(),
    private val kimiSearchClient: KimiWebSearchClient = KimiWebSearchClient.createDefault(),
) : McpToolset {
    @McpTool(name = "openai_usage_quota")
    @McpDescription(description = "Returns the latest OpenAI usage quota response JSON.")
    fun openai_usage_quota(): String {
        return quotaResult(QuotaProviderType.OPEN_AI, "No usage response available")
    }

    @McpTool(name = "opencode_usage_quota")
    @McpDescription(description = "Returns the latest OpenCode Go usage quota response JSON.")
    fun opencode_usage_quota(): String {
        return quotaResult(QuotaProviderType.OPEN_CODE, "No OpenCode usage response available") { service ->
            val quota = service.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota ?: return@quotaResult null
            runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), quota) }.getOrNull()
        }
    }

    @McpTool(name = "ollama_usage_quota")
    @McpDescription(description = "Returns the latest Ollama Cloud usage quota response JSON.")
    fun ollama_usage_quota(): String {
        return quotaResult(QuotaProviderType.OLLAMA, "No Ollama usage response available") { service ->
            val quota = service.getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota ?: return@quotaResult null
            runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota) }.getOrNull()
        }
    }

    @McpTool(name = "zai_usage_quota")
    @McpDescription(description = "Returns the latest Z.ai usage quota response JSON.")
    fun zai_usage_quota(): String {
        return quotaResult(QuotaProviderType.ZAI, "No Z.ai usage response available")
    }

    @McpTool(name = "minimax_usage_quota")
    @McpDescription(description = "Returns the latest MiniMax usage quota response JSON.")
    fun minimax_usage_quota(): String {
        return quotaResult(QuotaProviderType.MINIMAX, "No MiniMax usage response available")
    }

    @McpTool(name = "kimi_usage_quota")
    @McpDescription(description = "Returns the latest Kimi usage quota response JSON.")
    fun kimi_usage_quota(): String {
        return quotaResult(QuotaProviderType.KIMI, "No Kimi usage response available")
    }

    @McpTool(name = "github_usage_quota")
    @McpDescription(description = "Returns the latest GitHub Copilot usage quota response JSON.")
    fun github_usage_quota(): String {
        return quotaResult(QuotaProviderType.GITHUB, "No GitHub usage response available")
    }

    @McpTool(name = "cursor_usage_quota")
    @McpDescription(description = "Returns the latest Cursor usage quota response JSON.")
    fun cursor_usage_quota(): String {
        return quotaResult(QuotaProviderType.CURSOR, "No Cursor usage response available") { service ->
            service.getLastResponseJson(QuotaProviderType.CURSOR)?.let(CursorQuotaClient::normalizeRawJson)
        }
    }

    @McpTool(name = "codex_web_search")
    @McpDescription(description = "Runs a Codex subscription-backed web search using the existing OpenAI login and returns the Codex JSON response.")
    fun codex_web_search(
        @McpDescription(description = "Search query to send to Codex web search.") query: String,
    ): String {
        return codexResult(codexClient.webSearch(query))
    }

    @McpTool(name = "codex_image_generation")
    @McpDescription(description = "Generates an image through Codex. If targetFile is provided, writes the image there and returns JSON metadata; otherwise returns the Codex JSON response including b64_json data.")
    fun codex_image_generation(
        @McpDescription(description = "Image prompt to send to Codex image generation.") prompt: String,
        @McpDescription(description = "Optional target image file path. Relative paths resolve against the open project root when available. Leave blank to return b64_json in the response. The extension selects any image format supported by the standard JDK ImageIO writers, such as png.") targetFile: String? = null,
    ): String {
        return codexResult(codexClient.imageGeneration(prompt, targetFile, projectBaseDirectory()))
    }

    @McpTool(name = "kimi_web_search")
    @McpDescription(description = "Runs a Kimi subscription-backed web search using the existing Kimi login and returns the Kimi JSON response.")
    fun kimi_web_search(
        @McpDescription(description = "Search query to send to Kimi web search.") query: String,
        @McpDescription(description = "Number of search results to request. Values are clamped to Kimi's 1-20 range.") limit: Int = KimiWebSearchClient.DEFAULT_LIMIT,
        @McpDescription(description = "Whether to include fetched page content in the search results. This can substantially increase response size.") includeContent: Boolean = false,
    ): String {
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

    private fun quotaResult(
        type: QuotaProviderType,
        emptyMessage: String,
        json: (QuotaUsageService) -> String? = { it.getLastResponseJson(type) },
    ): String {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(type)

        val error = usageService.getLastError(type)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val payload = json(usageService)
        if (payload.isNullOrBlank()) {
            return errorResult(emptyMessage)
        }
        return successResult(payload)
    }

    private fun codexResult(response: CodexMcpClient.CodexMcpResponse): String {
        return response.body
    }

    private fun projectBaseDirectory(): Path? {
        return ProjectManager.getInstance().openProjects.firstOrNull()
            ?.basePath
            ?.let(Path::of)
    }

    private fun successResult(text: String): String {
        return text
    }

    private fun errorResult(errorMessage: String): String {
        return buildJsonObject { put("error", errorMessage) }.toString()
    }
}
