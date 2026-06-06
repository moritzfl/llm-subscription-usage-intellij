package de.moritzf.quota.idea.mcp

import com.intellij.openapi.diagnostic.Logger

internal object McpServerUrlResolver {
    private val logger = Logger.getInstance(McpServerUrlResolver::class.java)

    fun currentEndpoints(): McpServerEndpoints? {
        return runCatching {
            val serviceClass = Class.forName("com.intellij.mcpserver.impl.McpServerService")
            val companion = serviceClass.getField("Companion").get(null)
            val service = companion.javaClass.getMethod("getInstance").invoke(companion)
            val isRunning = serviceClass.getMethod("isRunning").invoke(service) as? Boolean ?: false
            if (!isRunning) {
                return null
            }

            val port = serviceClass.getMethod("getPort").invoke(service) as Int
            val sseUrl = runCatching {
                serviceClass.getMethod("getServerSseUrl").invoke(service) as? String
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "http://localhost:$port/sse"
            McpServerEndpoints(sseUrl = sseUrl, port = port)
        }.onFailure { error ->
            logger.debug("IntelliJ MCP server URL is not available", error)
        }.getOrNull()
    }
}
