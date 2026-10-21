package de.moritzf.quota.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal object McpJson {
    fun error(message: String): String =
        JsonSupport.json.encodeToString(McpErrorResponse(RawResponseRedactor.redactText(message)))

    /** Known non-JSON formats must be parsed by the provider before reaching this boundary. */
    fun providerJson(body: String): String {
        return runCatching { JsonSupport.json.parseToJsonElement(body) }
            .map { body }
            .getOrElse { error("Provider response could not be parsed as JSON.") }
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
            )
        )
    }

    /** Wrapper for a vision model's answer; provider answers are plain text, not provider JSON. */
    fun visionResult(provider: String, model: String, content: String): String {
        return JsonSupport.json.encodeToString(McpVisionResultResponse(provider, model, content))
    }
}

@Serializable
internal data class McpAccountToolStatus(
    val id: String,
    val type: String,
    val name: String,
    val label: String,
    @SerialName("default") val isDefault: Boolean,
    @SerialName("allow_failover") val allowFailover: Boolean,
    @SerialName("quota_configured") val quotaConfigured: Boolean,
    @SerialName("quota_supported") val quotaSupported: Boolean = true,
    @SerialName("proxy_supported") val proxySupported: Boolean = false,
    @SerialName("web_search_available") val webSearchAvailable: Boolean,
    @SerialName("web_search_type") val webSearchType: String? = null,
    @SerialName("web_fetch_available") val webFetchAvailable: Boolean = false,
    @SerialName("image_generation_available") val imageGenerationAvailable: Boolean,
    @SerialName("video_generation_available") val videoGenerationAvailable: Boolean,
    @SerialName("speech_to_text_available") val speechToTextAvailable: Boolean = false,
    @SerialName("text_to_speech_available") val textToSpeechAvailable: Boolean = false,
    @SerialName("document_to_markdown_available") val documentToMarkdownAvailable: Boolean = false,
    @SerialName("vision_available") val visionAvailable: Boolean = false,
    val reason: String? = null,
    @SerialName("snapshot_age_ms") val snapshotAgeMs: Long? = null,
    @SerialName("fetched_at") val fetchedAt: String? = null,
    @SerialName("quota_available") val quotaAvailable: Boolean? = null,
    @SerialName("limiting_pool") val limitingPool: String? = null,
    @SerialName("usage_percent") val usagePercent: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    @SerialName("exhaustion_reason") val exhaustionReason: String? = null,
)

@Serializable private data class McpErrorResponse(val error: String)

@Serializable
internal data class McpVisionResultResponse(
    val provider: String,
    val model: String,
    val content: String,
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
