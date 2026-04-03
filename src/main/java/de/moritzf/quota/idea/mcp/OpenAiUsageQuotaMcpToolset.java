package de.moritzf.quota.idea.mcp;

import com.intellij.mcpserver.McpToolset;
import com.intellij.mcpserver.McpToolCallResult;
import com.intellij.mcpserver.McpToolCallResultContent;
import com.intellij.mcpserver.annotations.McpDescription;
import com.intellij.mcpserver.annotations.McpTool;
import de.moritzf.quota.idea.QuotaUsageService;

/**
 * Exposes the latest OpenAI usage JSON through IntelliJ's MCP server.
 */
public final class OpenAiUsageQuotaMcpToolset implements McpToolset {
    @McpTool(name = "openai_usage_quota")
    @McpDescription(description = "Returns the latest OpenAI usage quota response JSON.")
    public McpToolCallResult openai_usage_quota() {
        QuotaUsageService usageService = QuotaUsageService.getInstance();
        usageService.refreshNowBlocking();

        String error = usageService.getLastError();
        if (error != null && !error.isBlank()) {
            return errorResult(error);
        }

        String json = usageService.getLastResponseJson();
        if (json == null || json.isBlank()) {
            return errorResult("No usage response available");
        }
        return successResult(json);
    }

    private static McpToolCallResult successResult(String text) {
        return new McpToolCallResult(
                new McpToolCallResultContent[]{new McpToolCallResultContent.Text(text)},
                null,
                false
        );
    }

    private static McpToolCallResult errorResult(String errorMessage) {
        String errorJson = "{\"error\":\"" + escapeJson(errorMessage) + "\"}";
        return new McpToolCallResult(
                new McpToolCallResultContent[]{new McpToolCallResultContent.Text(errorJson)},
                null,
                true
        );
    }

    private static String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
