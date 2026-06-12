package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Exposes the latest subscription usage JSON through IntelliJ's MCP server.
 * Each tool must stay a discrete annotated method; the bodies delegate to [quotaResult].
 */
class OpenAiUsageQuotaMcpToolset : McpToolset {
    @McpTool(name = "openai_usage_quota")
    @McpDescription(description = "Returns the latest OpenAI usage quota response JSON.")
    fun openai_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.OPEN_AI, "No usage response available")
    }

    @McpTool(name = "opencode_usage_quota")
    @McpDescription(description = "Returns the latest OpenCode Go usage quota response JSON.")
    fun opencode_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.OPEN_CODE, "No OpenCode usage response available") { service ->
            val quota = service.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota ?: return@quotaResult null
            runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), quota) }.getOrNull()
        }
    }

    @McpTool(name = "ollama_usage_quota")
    @McpDescription(description = "Returns the latest Ollama Cloud usage quota response JSON.")
    fun ollama_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.OLLAMA, "No Ollama usage response available") { service ->
            val quota = service.getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota ?: return@quotaResult null
            runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota) }.getOrNull()
        }
    }

    @McpTool(name = "zai_usage_quota")
    @McpDescription(description = "Returns the latest Z.ai usage quota response JSON.")
    fun zai_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.ZAI, "No Z.ai usage response available")
    }

    @McpTool(name = "minimax_usage_quota")
    @McpDescription(description = "Returns the latest MiniMax usage quota response JSON.")
    fun minimax_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.MINIMAX, "No MiniMax usage response available")
    }

    @McpTool(name = "kimi_usage_quota")
    @McpDescription(description = "Returns the latest Kimi usage quota response JSON.")
    fun kimi_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.KIMI, "No Kimi usage response available")
    }

    @McpTool(name = "github_usage_quota")
    @McpDescription(description = "Returns the latest GitHub Copilot usage quota response JSON.")
    fun github_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.GITHUB, "No GitHub usage response available")
    }

    @McpTool(name = "cursor_usage_quota")
    @McpDescription(description = "Returns the latest Cursor usage quota response JSON.")
    fun cursor_usage_quota(): McpToolCallResult {
        return quotaResult(QuotaProviderType.CURSOR, "No Cursor usage response available") { service ->
            service.getLastResponseJson(QuotaProviderType.CURSOR)?.let(CursorQuotaClient::normalizeRawJson)
        }
    }

    private fun quotaResult(
        type: QuotaProviderType,
        emptyMessage: String,
        json: (QuotaUsageService) -> String? = { it.getLastResponseJson(type) },
    ): McpToolCallResult {
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

    private fun successResult(text: String): McpToolCallResult {
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(text)), null, false)
    }

    private fun errorResult(errorMessage: String): McpToolCallResult {
        val errorJson = buildJsonObject { put("error", errorMessage) }.toString()
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(errorJson)), null, true)
    }
}
