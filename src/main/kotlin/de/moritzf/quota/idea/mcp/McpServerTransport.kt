package de.moritzf.quota.idea.mcp

/**
 * URL variants commonly used by MCP clients.
 */
enum class McpServerTransport(
    val displayName: String,
    private val pathSuffix: String,
) {
    SSE("SSE", "/sse"),
    STREAMABLE_HTTP("Streamable HTTP", "/mcp"),
    ;

    fun urlFor(endpoints: McpServerEndpoints): String {
        return when (this) {
            SSE -> endpoints.sseUrl
            STREAMABLE_HTTP -> endpoints.serverBaseUrl + pathSuffix
        }
    }

    override fun toString(): String = displayName

    companion object {
        fun fromStorageValue(value: String?): McpServerTransport {
            if (value.isNullOrBlank()) {
                return SSE
            }
            val normalized = value.trim().replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { transport ->
                transport.name.equals(normalized, ignoreCase = true) ||
                    transport.displayName.equals(value, ignoreCase = true) ||
                    (transport == STREAMABLE_HTTP && value.equals("http streaming", ignoreCase = true))
            } ?: SSE
        }
    }
}

data class McpServerEndpoints(
    val sseUrl: String,
    val port: Int,
) {
    val serverBaseUrl: String = sseUrl.trim().removeSuffix("/sse").trimEnd('/')
}
