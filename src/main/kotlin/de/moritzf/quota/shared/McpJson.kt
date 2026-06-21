package de.moritzf.quota.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

internal object McpJson {
    fun error(message: String): String = JsonSupport.json.encodeToString(McpErrorResponse(message))

    fun providerJsonOrRaw(body: String): String {
        return runCatching { JsonSupport.json.parseToJsonElement(body) }
            .map { body }
            .getOrElse { JsonSupport.json.encodeToString(McpRawProviderResponse(body)) }
    }

    fun webSearchStatus(statuses: List<McpWebSearchToolStatus>): String {
        return JsonSupport.json.encodeToString(
            McpWebSearchStatusResponse(
                availableTools = statuses.filter { it.available }.map { it.tool },
                tools = statuses,
            ),
        )
    }

}

@Serializable
internal data class McpWebSearchToolStatus(
    val tool: String,
    val provider: String,
    val configured: Boolean,
    val available: Boolean,
    val reason: String? = null,
)

@Serializable
private data class McpErrorResponse(
    val error: String,
)

@Serializable
private data class McpRawProviderResponse(
    @SerialName("raw_response") val rawResponse: String,
)

@Serializable
private data class McpWebSearchStatusResponse(
    val check: String = "credentials",
    val note: String = "This status does not call provider search APIs.",
    @SerialName("available_tools") val availableTools: List<String>,
    val tools: List<McpWebSearchToolStatus>,
)
