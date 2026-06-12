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
 */
class OpenAiUsageQuotaMcpToolset : McpToolset {
    @McpTool(name = "openai_usage_quota")
    @McpDescription(description = "Returns the latest OpenAI usage quota response JSON.")
    fun openai_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.OPEN_AI)

        val error = usageService.getLastError(QuotaProviderType.OPEN_AI)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson(QuotaProviderType.OPEN_AI)
        if (json.isNullOrBlank()) {
            return errorResult("No usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "opencode_usage_quota")
    @McpDescription(description = "Returns the latest OpenCode Go usage quota response JSON.")
    fun opencode_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.OPEN_CODE)

        val error = usageService.getLastError(QuotaProviderType.OPEN_CODE)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val quota = usageService.getLastQuota(QuotaProviderType.OPEN_CODE) as? OpenCodeQuota
            ?: return errorResult("No OpenCode usage response available")
        val json = runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), quota) }.getOrNull()
            ?: return errorResult("No OpenCode usage response available")
        return successResult(json)
    }

    @McpTool(name = "ollama_usage_quota")
    @McpDescription(description = "Returns the latest Ollama Cloud usage quota response JSON.")
    fun ollama_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.OLLAMA)

        val error = usageService.getLastError(QuotaProviderType.OLLAMA)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val quota = usageService.getLastQuota(QuotaProviderType.OLLAMA) as? OllamaQuota
            ?: return errorResult("No Ollama usage response available")
        val json = runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota) }.getOrNull()
            ?: return errorResult("No Ollama usage response available")
        return successResult(json)
    }

    @McpTool(name = "zai_usage_quota")
    @McpDescription(description = "Returns the latest Z.ai usage quota response JSON.")
    fun zai_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.ZAI)

        val error = usageService.getLastError(QuotaProviderType.ZAI)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson(QuotaProviderType.ZAI)
        if (json.isNullOrBlank()) {
            return errorResult("No Z.ai usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "minimax_usage_quota")
    @McpDescription(description = "Returns the latest MiniMax usage quota response JSON.")
    fun minimax_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.MINIMAX)

        val error = usageService.getLastError(QuotaProviderType.MINIMAX)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson(QuotaProviderType.MINIMAX)
        if (json.isNullOrBlank()) {
            return errorResult("No MiniMax usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "kimi_usage_quota")
    @McpDescription(description = "Returns the latest Kimi usage quota response JSON.")
    fun kimi_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.KIMI)

        val error = usageService.getLastError(QuotaProviderType.KIMI)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson(QuotaProviderType.KIMI)
        if (json.isNullOrBlank()) {
            return errorResult("No Kimi usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "github_usage_quota")
    @McpDescription(description = "Returns the latest GitHub Copilot usage quota response JSON.")
    fun github_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.GITHUB)

        val error = usageService.getLastError(QuotaProviderType.GITHUB)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson(QuotaProviderType.GITHUB)
        if (json.isNullOrBlank()) {
            return errorResult("No GitHub usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "cursor_usage_quota")
    @McpDescription(description = "Returns the latest Cursor usage quota response JSON.")
    fun cursor_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshBlocking(QuotaProviderType.CURSOR)

        val error = usageService.getLastError(QuotaProviderType.CURSOR)
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val rawJson = usageService.getLastResponseJson(QuotaProviderType.CURSOR)
        if (rawJson.isNullOrBlank()) {
            return errorResult("No Cursor usage response available")
        }
        return successResult(CursorQuotaClient.normalizeRawJson(rawJson))
    }

    private fun successResult(text: String): McpToolCallResult {
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(text)), null, false)
    }

    private fun errorResult(errorMessage: String): McpToolCallResult {
        val errorJson = buildJsonObject { put("error", errorMessage) }.toString()
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(errorJson)), null, true)
    }
}
