package de.moritzf.quota.azure

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class AzureQuota(
    val account: AzureAccountIdentity? = null,
    val windows: List<AzureUsageWindow> = emptyList(),
    val models: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    override var fetchedAt: Instant? = null,
    override var rawJson: String? = null,
) : ProviderQuota {
    override fun hasUsageState(): Boolean = windows.any { it.usagePercent != null }

    fun primaryWindow(): AzureUsageWindow? =
        windows.filter { it.kind == AzureUsageWindow.LIVE && it.usagePercent != null }.maxByOrNull { it.usagePercent!! }
            ?: windows.filter { it.usagePercent != null }.maxByOrNull { it.usagePercent!! }

    override fun usageFraction(): Double? = primaryWindow()?.usagePercent?.div(100.0)

    override fun activityWindows(): Map<String, Double> = windows.mapNotNull { window ->
        window.usagePercent?.let { "${window.kind}/${window.id}" to it / 100.0 }
    }.toMap()
}

@Serializable
data class AzureAccountIdentity(
    val userName: String? = null,
    val userType: String? = null,
    val subscriptionId: String? = null,
    val subscriptionName: String? = null,
    val tenantId: String? = null,
    val tier: String? = null,
)

@Serializable
data class AzureUsageWindow(
    val id: String,
    val label: String,
    val kind: String,
    val used: Double? = null,
    val limit: Double? = null,
    val remaining: Double? = null,
    val unit: String? = null,
    val resetsAt: Instant? = null,
) {
    val usagePercent: Double?
        get() {
            val cap = limit?.takeIf { it > 0 } ?: return null
            val consumed = when (kind) {
                LIVE -> remaining?.let { cap - it }
                else -> used
            } ?: return null
            return (consumed / cap * 100.0).takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        }

    companion object {
        const val ALLOCATION = "allocation"
        const val LIVE = "live"
        const val DEPLOYMENT = "deployment"
    }
}

internal data class AzureRateLimitSnapshot(
    val model: String?,
    val limitTokens: Double?,
    val remainingTokens: Double?,
    val limitRequests: Double?,
    val remainingRequests: Double?,
    val resetTokensSeconds: Long?,
)

internal object AzureLiveUsage {
    private val values = ConcurrentHashMap<String, AzureRateLimitSnapshot>()

    fun record(accountKey: String, snapshot: AzureRateLimitSnapshot) {
        if (accountKey.isBlank()) return
        if (snapshot.limitTokens == null && snapshot.limitRequests == null) return
        values[accountKey] = snapshot
    }

    fun read(accountKey: String): AzureRateLimitSnapshot? = values[accountKey]
}

internal fun parseAzureUsages(raw: String): Pair<List<AzureUsageWindow>, List<String>> {
    val root = azureObject(raw) ?: return emptyList<AzureUsageWindow>() to listOf("Usage response was not JSON.")
    val value = root["value"] as? JsonArray ?: return emptyList<AzureUsageWindow>() to listOf("Usage response had no quota lines.")
    val warnings = mutableListOf<String>()
    val parsed = mutableListOf<AzureUsageWindow>()
    var skipped = 0
    for (element in value) {
        val item = element as? JsonObject
        val name = item?.get("name") as? JsonObject
        val id = name?.text("value")
        val limit = item?.get("limit")?.lenientDoubleOrNull()
        if (item == null || id == null || limit == null || limit <= 0) {
            skipped++
            continue
        }
        val current = item["currentValue"]?.lenientDoubleOrNull()
        if (item["currentValue"] != null && current == null) warnings += "Usage amount unavailable for $id."
        parsed += AzureUsageWindow(
            id = id,
            label = name.text("localizedValue") ?: id,
            kind = AzureUsageWindow.ALLOCATION,
            used = current,
            limit = limit,
            unit = item.text("unit"),
        )
    }
    if (skipped > 0 && parsed.isEmpty() && value.isNotEmpty()) warnings += "No readable quota lines in the usage response."
    val openai = parsed.filter { it.looksLikeModelQuota() }
    if (openai.isNotEmpty()) return openai to warnings
    if (parsed.isNotEmpty()) {
        warnings += "Quota lines were not labeled as Azure OpenAI; showing them anyway."
        return parsed to warnings
    }
    return emptyList<AzureUsageWindow>() to warnings
}

internal fun parseAzureModels(raw: String): List<String> {
    val root = azureObject(raw) ?: return emptyList()
    val data = (root["data"] as? JsonArray) ?: (root["value"] as? JsonArray) ?: return emptyList()
    return data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        item.text("id") ?: item.text("name")
    }.filter { AZURE_DEPLOYMENT_NAME.matches(it) }.distinct()
}

internal fun parseAzureDeployments(raw: String): List<AzureUsageWindow> {
    val root = azureObject(raw) ?: return emptyList()
    val value = root["value"] as? JsonArray ?: return emptyList()
    return value.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.text("name") ?: return@mapNotNull null
        val sku = item["sku"] as? JsonObject
        val capacity = sku?.get("capacity")?.lenientDoubleOrNull()
        AzureUsageWindow(
            id = name,
            label = name,
            kind = AzureUsageWindow.DEPLOYMENT,
            used = capacity?.times(1_000),
            unit = if (capacity != null) "TPM allocated" else null,
        )
    }
}

internal fun parseAzureQuotaTier(raw: String): String? {
    val root = azureObject(raw) ?: return null
    val value = root["value"] as? JsonArray
    val first = value?.firstOrNull() as? JsonObject ?: root
    val properties = first["properties"] as? JsonObject ?: return null
    return properties.text("currentTierName")
}

internal fun parseAzureResources(raw: String): List<AzureResourceRef> {
    val root = azureObject(raw) ?: return emptyList()
    val value = root["value"] as? JsonArray ?: return emptyList()
    return value.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.text("name") ?: return@mapNotNull null
        val properties = item["properties"] as? JsonObject
        val kind = item.text("kind")
        val endpoint = properties?.text("endpoint")
        if (kind != null && !kind.equals("OpenAI", ignoreCase = true) && !kind.equals("AIServices", ignoreCase = true)) {
            return@mapNotNull null
        }
        AzureResourceRef(
            name = name,
            location = item.text("location")?.lowercase(),
            endpoint = endpoint,
            resourceGroup = item.text("id")?.substringAfter("/resourceGroups/", "")?.substringBefore('/')?.ifBlank { null },
        )
    }
}

internal data class AzureResourceRef(
    val name: String,
    val location: String?,
    val endpoint: String?,
    val resourceGroup: String?,
)

internal fun liveWindow(snapshot: AzureRateLimitSnapshot, now: Instant): AzureUsageWindow? {
    val limit = snapshot.limitTokens ?: snapshot.limitRequests ?: return null
    val remaining = if (snapshot.limitTokens != null) snapshot.remainingTokens else snapshot.remainingRequests
    return AzureUsageWindow(
        id = snapshot.model ?: "deployment",
        label = snapshot.model ?: "Live rate limit",
        kind = AzureUsageWindow.LIVE,
        limit = limit,
        remaining = remaining,
        unit = if (snapshot.limitTokens != null) "tokens" else "requests",
        resetsAt = snapshot.resetTokensSeconds?.takeIf { it >= 0 }?.let { now.plus(kotlin.time.Duration.parse("${it}s")) },
    )
}

internal fun parseRateLimitHeaders(headers: Map<String, List<String>>, model: String?): AzureRateLimitSnapshot? {
    fun first(name: String): Double? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.firstOrNull()?.toDoubleOrNull()?.takeIf { it.isFinite() }
    val snapshot = AzureRateLimitSnapshot(
        model = model,
        limitTokens = first("x-ratelimit-limit-tokens"),
        remainingTokens = first("x-ratelimit-remaining-tokens"),
        limitRequests = first("x-ratelimit-limit-requests"),
        remainingRequests = first("x-ratelimit-remaining-requests"),
        resetTokensSeconds = first("x-ratelimit-reset-tokens")?.toLong(),
    )
    if (snapshot.limitTokens == null && snapshot.limitRequests == null) return null
    return snapshot
}

private fun AzureUsageWindow.looksLikeModelQuota(): Boolean {
    val text = "$id $label".lowercase()
    return text.contains("openai") || text.contains("token") || text.contains("gpt") || text.contains("tpm")
}

private fun azureObject(raw: String): JsonObject? =
    runCatching { JsonSupport.json.parseToJsonElement(raw.trim().removePrefix("\uFEFF")) as? JsonObject }.getOrNull()

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

internal fun azureQuotaJson(quota: AzureQuota): String =
    JsonSupport.json.encodeToString(AzureQuota.serializer(), quota.copy(rawJson = null, fetchedAt = quota.fetchedAt ?: Clock.System.now()))
