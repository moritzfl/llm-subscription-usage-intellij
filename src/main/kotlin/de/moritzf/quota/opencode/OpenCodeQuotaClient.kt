package de.moritzf.quota.opencode

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.lenientDoubleOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant

/** Reads Go meters and prepaid Zen balance from the Console JSON API. */
open class OpenCodeQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    open fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
        val now = Clock.System.now()
        val warnings = mutableListOf<String>()
        var failureStatus: Int? = null
        val failedBodies = mutableMapOf<String, String?>()
        fun section(path: String): String? = try {
            get(path, accessToken, workspaceId)
        } catch (exception: java.io.IOException) {
            // Authentication still gets one token refresh; other failures only affect this section.
            val status = (exception as? OpenCodeQuotaException)?.statusCode ?: 0
            if (status == 401) throw exception
            failureStatus = failureStatus ?: status
            failedBodies[path] = (exception as? OpenCodeQuotaException)?.rawBody
            warnings += exception.message ?: "OpenCode $path is unavailable"
            null
        }
        val goBody = section("api/go/status")
        val quota = goBody?.let { parseQuotaResponse(it, now) } ?: OpenCodeQuota()
        warnings += quota.warnings
        val billingBody = section("api/billing/status")
        billingBody?.let { body ->
            val billing = parseBilling(body)
            quota.availableBalance = billing.first
            billing.second?.let(warnings::add)
        }
        val rawGo = goBody ?: failedBodies["api/go/status"]
        val rawBilling = billingBody ?: failedBodies["api/billing/status"]
        val raw = buildRawResponse(rawGo, rawBilling)
        if (!quota.hasUsageState() && !quota.hasAvailableBalance() && warnings.isNotEmpty()) {
            throw OpenCodeQuotaException(warnings.joinToString("; "), failureStatus ?: 200, raw)
        }
        quota.warnings = warnings
        quota.fetchedAt = now
        quota.rawGoJson = rawGo
        quota.rawBillingJson = rawBilling
        quota.rawJson = raw
        return quota
    }

    open fun fetchWorkspaces(accessToken: String): List<OpenCodeWorkspace> {
        val body = get("api/orgs", accessToken)
        return try {
            val entries = JsonSupport.json.parseToJsonElement(body) as JsonArray
            JsonSupport.decodeListItemsLeniently(entries, OpenCodeWorkspace.serializer()).filter { it.id.isNotBlank() }
        } catch (exception: Exception) {
            throw OpenCodeQuotaException("Could not parse OpenCode organizations", 200, body, exception)
        }
    }

    open fun discoverWorkspaceId(accessToken: String): String = fetchWorkspaces(accessToken)
        .sortedWith(compareBy({ it.name }, { it.id })).firstOrNull()?.id
        ?: throw OpenCodeQuotaException("No OpenCode organizations found. Sign in to OpenCode Console first.", 200)

    private fun get(path: String, accessToken: String, workspaceId: String? = null): String {
        require(accessToken.isNotBlank()) { "OpenCode access token is missing" }
        val request = HttpRequest.newBuilder(endpoint.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .apply { if (workspaceId != null) header("x-org-id", workspaceId) }
            .GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status !in 200..299) {
            val message = when (status) {
                401 -> "OpenCode session expired. Sign in again."
                403 -> "OpenCode denied access to $path for this organization (HTTP 403)."
                else -> "OpenCode $path request failed: HTTP $status"
            }
            throw OpenCodeQuotaException(message, status, response.body())
        }
        return response.body()
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://opencode.ai/console/")

        fun parseQuotaResponse(body: String, now: Instant = Clock.System.now()): OpenCodeQuota {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull()
            if (root == JsonNull) return OpenCodeQuota()
            fun unreadable() = OpenCodeQuota(warnings = listOf("Go usage response could not be read"))
            if (root !is JsonObject) return unreadable()
            val useBalance = (root["useBalance"] as? JsonPrimitive)?.booleanOrNull ?: false
            if (root["access"] == JsonNull) return OpenCodeQuota(useBalance = useBalance)
            val access = root["access"] as? JsonObject ?: return unreadable()
            val meters = access["meters"] as? JsonObject ?: return unreadable()
            val warnings = mutableListOf<String>()
            fun window(name: String): OpenCodeUsageWindow? {
                val value = meters[name]?.takeUnless { it == JsonNull } ?: return null
                val meter = value as? JsonObject
                val limit = meter?.get("limitMicroCents")?.lenientDoubleOrNull()
                val used = meter?.get("usedMicroCents")?.lenientDoubleOrNull()
                if (limit == null || used == null || limit < 0 || used < 0) {
                    warnings += "Go $name usage is unavailable"
                    return null
                }
                if (limit == 0.0) return null
                val percent = (used / limit * 100.0).takeIf { it.isFinite() } ?: run {
                    warnings += "Go $name usage is unavailable"
                    return null
                }
                val reset = meter["resetsAt"]?.takeUnless { it == JsonNull }
                val periodEnd = if (name == "month") access["endsAt"]?.takeUnless { it == JsonNull } else null
                fun resetTime(value: JsonElement?): Instant? =
                    (value as? JsonPrimitive)?.content?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val resetAt = resetTime(reset) ?: resetTime(periodEnd)
                if (resetAt == null && (reset != null || periodEnd != null)) warnings += "Go $name reset time is unavailable"
                val seconds = resetAt?.let { (it - now).inWholeSeconds.coerceAtLeast(0) } ?: 0
                return OpenCodeUsageWindow(if (percent >= 100) "rate-limited" else "ok", seconds, percent)
            }
            if (listOf("fiveHour", "week", "month").none { meters.containsKey(it) }) return unreadable()
            return OpenCodeQuota(
                rollingUsage = window("fiveHour"),
                weeklyUsage = window("week"),
                monthlyUsage = window("month"),
                useBalance = useBalance,
                warnings = warnings,
            )
        }

        internal fun parseBillingBalance(body: String): Long? = parseBilling(body).first

        private fun parseBilling(body: String): Pair<Long?, String?> {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return null to "Zen balance response could not be read"
            val billingMode = (root["billingMode"] as? JsonPrimitive)?.content
            val mode = (root["mode"] as? JsonPrimitive)?.content
            if (billingMode in setOf("seat", "credit", "legacy") || mode in setOf("invoiceable", "none")) return null to null
            // Missing or changed ancillary metadata must not hide a usable balance field.
            // Available credit is a different quantity and must never stand in for wallet balance.
            val balance = (root["balanceMicroCents"] as? JsonPrimitive)?.content?.trim()?.toBigDecimalOrNull()
                ?.let { runCatching { it.longValueExact() }.getOrNull() }
            return if (balance != null) balance to null else null to "Zen balance is unavailable"
        }

        fun buildRawResponse(goBody: String?, billingBody: String?): String? {
            val sections = listOfNotNull(
                goBody?.let { "go" to it },
                billingBody?.let { "billing" to it },
            ).associate { (name, body) ->
                name to (runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull()
                    ?: JsonObject(mapOf("raw_response" to JsonPrimitive(body))))
            }
            return sections.takeIf { it.isNotEmpty() }?.let { JsonSupport.json.encodeToString(JsonObject(it)) }
        }
    }
}
