package de.moritzf.quota.azure

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.ProviderQuota
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@Serializable
data class AzureQuota(
    val account: AzureAccountIdentity? = null,
    val windows: List<AzureUsageWindow> = emptyList(),
    val models: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** True when this fetch read the resource deployment or model catalog. Not stored in the quota cache. */
    @Transient val modelCatalogRead: Boolean = false,
    override var fetchedAt: Instant? = null,
    @Transient override var rawJson: String? = null,
) : ProviderQuota {
    fun currentWindows(now: Instant = Clock.System.now()): List<AzureUsageWindow> = windows.filter {
        it.kind != AzureUsageWindow.LIVE || it.expiresAt?.let { expiry -> now < expiry } == true
    }

    /** ARM quota allocations are not consumption; only observed data-plane rate limits represent usage. */
    fun liveWindows(now: Instant = Clock.System.now()): List<AzureUsageWindow> = currentWindows(now).filter {
        it.kind == AzureUsageWindow.LIVE && it.usagePercent != null
    }

    override fun hasUsageState(): Boolean = liveWindows().isNotEmpty()

    fun primaryWindow(): AzureUsageWindow? = liveWindows().maxByOrNull { it.usagePercent!! }

    override fun usageFraction(): Double? = primaryWindow()?.usagePercent?.div(100.0)

    override fun activityWindows(): Map<String, Double> = liveWindows().associate { it.key to it.usagePercent!! / 100.0 }
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
    /** Deployment SKU scale. Not consumption. */
    val capacity: Double? = null,
    val remaining: Double? = null,
    val unit: String? = null,
    val resetsAt: Instant? = null,
    val location: String? = null,
    val resourceName: String? = null,
    val expiresAt: Instant? = null,
    val modelName: String? = null,
) {
    val key: String get() = listOf(kind, location.orEmpty(), resourceName.orEmpty(), id).joinToString("/")

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
    val resetRequestsSeconds: Long? = null,
    val observedAt: Instant = Clock.System.now(),
)

internal object AzureLiveUsage {
    private val values = ConcurrentHashMap<String, AzureRateLimitSnapshot>()

    fun record(accountKey: String, snapshot: AzureRateLimitSnapshot) {
        if (accountKey.isBlank()) return
        if (snapshot.limitTokens == null && snapshot.limitRequests == null) return
        values[accountKey] = snapshot
    }

    fun read(accountKey: String): AzureRateLimitSnapshot? {
        val snapshot = values[accountKey] ?: return null
        if (liveWindow(snapshot, Clock.System.now()) != null) return snapshot
        values.remove(accountKey, snapshot)
        return null
    }

    fun key(accountId: String, config: AzureAccountConfig): String =
        "$accountId|${AzureQuotaClient.catalogKey(config.subscriptionId, config)}"
}

internal fun parseAzureUsages(raw: String, location: String? = null): Pair<List<AzureUsageWindow>, List<String>> {
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
            label = listOfNotNull(name.text("localizedValue") ?: id, location).joinToString(" · "),
            kind = AzureUsageWindow.ALLOCATION,
            used = current,
            limit = limit,
            unit = item.text("unit"),
            location = location,
        )
    }
    if (skipped > 0 && parsed.isEmpty() && value.isNotEmpty()) warnings += "No readable quota lines in the usage response."
    val openai = parsed.filter { it.looksLikeModelQuota() }
    if (openai.isNotEmpty()) return openai to warnings
    if (parsed.isNotEmpty()) {
        warnings += "Quota lines were not labeled as Azure OpenAI."
        return parsed to warnings
    }
    return emptyList<AzureUsageWindow>() to warnings
}

internal fun parseAzureModels(raw: String): List<String>? {
    val root = azureObject(raw) ?: return null
    val data = (root["data"] as? JsonArray) ?: (root["value"] as? JsonArray) ?: return null
    return data.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        item.text("id") ?: item.text("name")
    }.filter { AZURE_DEPLOYMENT_NAME.matches(it) }.distinct()
}

internal fun parseAzureDeployments(raw: String, resource: AzureResourceRef? = null): List<AzureUsageWindow>? {
    val root = azureObject(raw) ?: return null
    val value = root["value"] as? JsonArray ?: return null
    return value.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val name = item.text("name") ?: return@mapNotNull null
        val sku = item["sku"] as? JsonObject
        val properties = item["properties"] as? JsonObject
        val model = properties?.get("model") as? JsonObject
        val capacity = sku?.get("capacity")?.lenientDoubleOrNull()
        AzureUsageWindow(
            id = name,
            label = listOfNotNull(name, resource?.name, resource?.location).joinToString(" · "),
            kind = AzureUsageWindow.DEPLOYMENT,
            capacity = capacity,
            // Capacity-to-TPM ratios vary by model; provisioned SKUs use PTUs instead.
            unit = if (sku?.text("name")?.contains("Provisioned", ignoreCase = true) == true) "PTU" else "capacity units",
            location = resource?.location,
            resourceName = resource?.name,
            modelName = model?.text("name"),
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
    fun window(limit: Double?, remaining: Double?, resetSeconds: Long?, unit: String): AzureUsageWindow? {
        if (limit == null || limit <= 0 || remaining == null) return null
        val resetsAt = resetSeconds?.takeIf { it >= 0 }?.let { snapshot.observedAt + it.seconds }
        val expiresAt = resetsAt ?: (snapshot.observedAt + 60.seconds)
        if (now >= expiresAt) return null
        return AzureUsageWindow(
            id = snapshot.model ?: "deployment",
            label = snapshot.model ?: "Live rate limit",
            kind = AzureUsageWindow.LIVE,
            limit = limit,
            remaining = remaining,
            unit = unit,
            resetsAt = resetsAt,
            expiresAt = expiresAt,
        )
    }
    return listOfNotNull(
        window(snapshot.limitTokens, snapshot.remainingTokens, snapshot.resetTokensSeconds, "tokens"),
        window(snapshot.limitRequests, snapshot.remainingRequests, snapshot.resetRequestsSeconds, "requests"),
    ).maxByOrNull { it.usagePercent ?: 0.0 }
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
        resetRequestsSeconds = first("x-ratelimit-reset-requests")?.toLong(),
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

internal fun buildAzureRawResponse(
    account: JsonElement? = null,
    resources: String? = null,
    usages: Map<String, String> = emptyMap(),
    quotaTiers: String? = null,
    deployments: Map<String, String> = emptyMap(),
    models: String? = null,
): String? {
    val root = buildJsonObject {
        account?.let { put("account", it) }
        jsonOrRaw(resources)?.let { put("resources", it) }
        objectOf(usages)?.let { put("usages", it) }
        jsonOrRaw(quotaTiers)?.let { put("quotaTiers", it) }
        objectOf(deployments)?.let { put("deployments", it) }
        jsonOrRaw(models)?.let { put("models", it) }
    }
    if (root.isEmpty()) return null
    return JsonSupport.json.encodeToString(JsonObject.serializer(), root)
}

private fun objectOf(sections: Map<String, String>): JsonObject? {
    val objectValue = buildJsonObject {
        sections.forEach { (name, body) -> jsonOrRaw(body)?.let { put(name, it) } }
    }
    return objectValue.takeIf { it.isNotEmpty() }
}

private fun jsonOrRaw(body: String?): JsonElement? {
    val value = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { JsonSupport.json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
}
