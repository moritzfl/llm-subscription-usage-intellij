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

    fun toolsStatus(providers: List<McpProviderToolStatus>): String {
        return JsonSupport.json.encodeToString(McpToolsStatusResponse(providers = providers))
    }

    fun accountToolsStatus(
        accounts: List<McpAccountToolStatus>,
        capability: String? = null,
        model: String? = null,
        resolvedAccountId: String? = null,
        fallbackReason: String? = null,
    ): String {
        return JsonSupport.json.encodeToString(
            McpAccountsToolsStatusResponse(
                accounts = accounts,
                capability = capability,
                model = model,
                resolvedAccountId = resolvedAccountId,
                fallbackReason = fallbackReason,
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
internal data class McpAccountToolStatus(
    val id: String,
    val type: String,
    val name: String,
    val label: String,
    @SerialName("default") val isDefault: Boolean,
    @SerialName("allow_failover") val allowFailover: Boolean,
    @SerialName("quota_configured") val quotaConfigured: Boolean,
    @SerialName("web_search_available") val webSearchAvailable: Boolean,
    @SerialName("web_search_type") val webSearchType: String? = null,
    @SerialName("image_generation_available") val imageGenerationAvailable: Boolean,
    @SerialName("video_generation_available") val videoGenerationAvailable: Boolean,
    @SerialName("speech_to_text_available") val speechToTextAvailable: Boolean = false,
    @SerialName("text_to_speech_available") val textToSpeechAvailable: Boolean = false,
    @SerialName("document_to_markdown_available") val documentToMarkdownAvailable: Boolean = false,
    val reason: String? = null,
    @SerialName("snapshot_age_ms") val snapshotAgeMs: Long? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    @SerialName("quota_available") val quotaAvailable: Boolean? = null,
    @SerialName("limiting_pool") val limitingPool: String? = null,
    @SerialName("usage_percent") val usagePercent: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("exhaustion_reason") val exhaustionReason: String? = null,
)

@Serializable
internal data class McpProviderToolStatus(
    val provider: String,
    @SerialName("quota_configured") val quotaConfigured: Boolean,
    @SerialName("web_search_available") val webSearchAvailable: Boolean,
    @SerialName("web_search_type") val webSearchType: String? = null,
    @SerialName("image_generation_available") val imageGenerationAvailable: Boolean,
    @SerialName("video_generation_available") val videoGenerationAvailable: Boolean,
    @SerialName("speech_to_text_available") val speechToTextAvailable: Boolean = false,
    @SerialName("text_to_speech_available") val textToSpeechAvailable: Boolean = false,
    @SerialName("document_to_markdown_available") val documentToMarkdownAvailable: Boolean = false,
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

@Serializable
private data class McpToolsStatusResponse(
    val check: String = "credentials",
    val note: String = "This status does not call provider APIs.",
    val providers: List<McpProviderToolStatus>,
)

@Serializable
private data class McpAccountsToolsStatusResponse(
    val check: String = "credentials",
    val note: String = "This status does not call provider APIs.",
    val accounts: List<McpAccountToolStatus>,
    val capability: String? = null,
    val model: String? = null,
    @SerialName("resolved_account_id") val resolvedAccountId: String? = null,
    @SerialName("fallback_reason") val fallbackReason: String? = null,
)
