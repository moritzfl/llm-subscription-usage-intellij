package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolCallResultContent
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
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
        usageService.refreshOpenAiBlocking()

        val error = usageService.getLastError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastResponseJson()
        if (json.isNullOrBlank()) {
            return errorResult("No usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "opencode_usage_quota")
    @McpDescription(description = "Returns the latest OpenCode Go usage quota response JSON.")
    fun opencode_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshOpenCodeBlocking()

        val error = usageService.getLastOpenCodeError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val quota = usageService.getLastOpenCodeQuota()
            ?: return errorResult("No OpenCode usage response available")
        val json = runCatching { JsonSupport.json.encodeToString(OpenCodeQuota.serializer(), quota) }.getOrNull()
            ?: return errorResult("No OpenCode usage response available")
        return successResult(json)
    }

    @McpTool(name = "ollama_usage_quota")
    @McpDescription(description = "Returns the latest Ollama Cloud usage quota response JSON.")
    fun ollama_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshOllamaBlocking()

        val error = usageService.getLastOllamaError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val quota = usageService.getLastOllamaQuota()
            ?: return errorResult("No Ollama usage response available")
        val json = runCatching { JsonSupport.json.encodeToString(OllamaQuota.serializer(), quota) }.getOrNull()
            ?: return errorResult("No Ollama usage response available")
        return successResult(json)
    }

    @McpTool(name = "zai_usage_quota")
    @McpDescription(description = "Returns the latest Z.ai usage quota response JSON.")
    fun zai_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshZaiBlocking()

        val error = usageService.getLastZaiError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastZaiResponseJson()
        if (json.isNullOrBlank()) {
            return errorResult("No Z.ai usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "minimax_usage_quota")
    @McpDescription(description = "Returns the latest MiniMax usage quota response JSON.")
    fun minimax_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshMiniMaxBlocking()

        val error = usageService.getLastMiniMaxError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastMiniMaxResponseJson()
        if (json.isNullOrBlank()) {
            return errorResult("No MiniMax usage response available")
        }
        return successResult(json)
    }

    @McpTool(name = "kimi_usage_quota")
    @McpDescription(description = "Returns the latest Kimi usage quota response JSON.")
    fun kimi_usage_quota(): McpToolCallResult {
        val usageService = QuotaUsageService.getInstance()
        usageService.refreshKimiBlocking()

        val error = usageService.getLastKimiError()
        if (!error.isNullOrBlank()) {
            return errorResult(error)
        }

        val json = usageService.getLastKimiResponseJson()
        if (json.isNullOrBlank()) {
            return errorResult("No Kimi usage response available")
        }
        return successResult(json)
    }

    private fun successResult(text: String): McpToolCallResult {
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(text)), null, false)
    }

    private fun errorResult(errorMessage: String): McpToolCallResult {
        val errorJson = buildJsonObject { put("error", errorMessage) }.toString()
        return McpToolCallResult(arrayOf(McpToolCallResultContent.Text(errorJson)), null, true)
    }
}
