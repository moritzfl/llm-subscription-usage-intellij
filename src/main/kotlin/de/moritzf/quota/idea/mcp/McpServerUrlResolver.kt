package de.moritzf.quota.idea.mcp

import com.intellij.openapi.diagnostic.Logger

internal object McpServerUrlResolver {
    private val logger = Logger.getInstance(McpServerUrlResolver::class.java)

    fun currentEndpoints(): McpServerEndpoints? {
        return currentStatus().endpoints
    }

    fun currentStatus(): McpServerStatus {
        return runCatching {
            val serviceClass = Class.forName("com.intellij.mcpserver.impl.McpServerService")
            val companion = serviceClass.getField("Companion").get(null)
            val service = companion.javaClass.getMethod("getInstance").invoke(companion)
            val isRunning = serviceClass.getMethod("isRunning").invoke(service) as? Boolean ?: false
            if (!isRunning) {
                return McpServerStatus(McpServerStatusState.NOT_RUNNING, "MCP server installed, but not running")
            }

            val port = serviceClass.getMethod("getPort").invoke(service) as Int
            val sseUrl = runCatching {
                serviceClass.getMethod("getServerSseUrl").invoke(service) as? String
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "http://localhost:$port/sse"
            McpServerStatus(
                state = McpServerStatusState.RUNNING,
                message = "MCP server running at $sseUrl",
                endpoints = McpServerEndpoints(sseUrl = sseUrl, port = port),
            )
        }.getOrElse { error ->
            statusForError(error).also { status ->
                if (status.state == McpServerStatusState.UNAVAILABLE) {
                    logger.debug("IntelliJ MCP server URL is not available", error)
                }
            }
        }
    }

    private fun statusForError(error: Throwable): McpServerStatus {
        return if (error.isMissingMcpServer()) {
            McpServerStatus(
                state = McpServerStatusState.NOT_INSTALLED_OR_DISABLED,
                message = "MCP server plugin not installed or disabled",
            )
        } else {
            McpServerStatus(
                state = McpServerStatusState.UNAVAILABLE,
                message = "MCP server status unavailable",
            )
        }
    }

    private fun Throwable.isMissingMcpServer(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ClassNotFoundException || current is NoClassDefFoundError) {
                return true
            }
            current = current.cause
        }
        return false
    }
}

internal data class McpServerStatus(
    val state: McpServerStatusState,
    val message: String,
    val endpoints: McpServerEndpoints? = null,
)

internal enum class McpServerStatusState {
    RUNNING,
    NOT_RUNNING,
    NOT_INSTALLED_OR_DISABLED,
    UNAVAILABLE,
}
