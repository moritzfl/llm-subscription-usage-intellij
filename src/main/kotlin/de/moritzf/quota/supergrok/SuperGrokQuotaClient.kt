package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class SuperGrokQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    open fun fetchQuota(accessToken: String?): SuperGrokQuota {
        val token = accessToken?.trim()?.takeIf { it.isNotBlank() }
            ?: throw SuperGrokQuotaException("Grok login required. Log in from SuperGrok settings.")

        val billingJson = getJson(token, BILLING_PATH, required = true)
            ?: throw SuperGrokQuotaException("Grok billing response changed.")
        val settingsJson = getJson(token, SETTINGS_PATH, required = false)
        val rawJson = combinedRawJson(billingJson, settingsJson)

        val quota = try {
            parseQuota(billingJson, settingsJson)
        } catch (exception: SuperGrokQuotaException) {
            throw SuperGrokQuotaException(exception.message ?: "Grok billing response changed.", 200, rawJson, exception)
        } catch (exception: Exception) {
            throw SuperGrokQuotaException("Grok billing response changed.", 200, rawJson, exception)
        }
        quota.rawJson = rawJson
        return quota
    }

    private fun getJson(accessToken: String, path: String, required: Boolean): String? {
        val request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("X-XAI-Token-Auth", TOKEN_AUTH_HEADER)
            .header("Accept", "application/json")
            .header("User-Agent", "LLM Subscription Usage")
            .GET()
            .build()

        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status, body)
        }
        if (status !in 200..299) {
            if (!required) return null
            throw SuperGrokQuotaException("Grok billing request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw SuperGrokQuotaException("Grok billing request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException("Grok billing request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        @JvmField
        val DEFAULT_BASE_URI: URI = URI.create("https://cli-chat-proxy.grok.com/v1/")

        private const val BILLING_PATH = "billing"
        private const val SETTINGS_PATH = "settings"
        private const val TOKEN_AUTH_HEADER = "xai-grok-cli"
        private const val AUTH_SOURCE = "xai-oauth-cli-proxy"

        fun parseQuota(
            billingJson: String,
            settingsJson: String? = null,
            fetchedAt: Instant = Clock.System.now(),
        ): SuperGrokQuota {
            val billing = runCatching { JsonSupport.json.decodeFromString<SuperGrokBillingResponseDto>(billingJson) }
                .getOrElse { throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson, it) }
            val config = billing.config ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson)
            val used = config.used?.valValue ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson)
            val limit = config.monthlyLimit?.valValue ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson)
            val onDemandCap = config.onDemandCap?.valValue ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson)
            if (limit <= 0) {
                throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson)
            }
            val resetsAt = config.billingPeriodEnd?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, billingJson)
            val periodDurationMs = periodDurationMillis(config.billingPeriodStart, config.billingPeriodEnd)
            val plan = parsePlan(settingsJson)

            return SuperGrokQuota(
                plan = plan.orEmpty(),
                authSource = AUTH_SOURCE,
                creditUsage = SuperGrokUsageWindow(
                    used = used,
                    limit = limit,
                    usagePercent = (used.toDouble() / limit.toDouble() * 100.0).coerceIn(0.0, 100.0),
                    resetsAt = resetsAt,
                    periodDurationMs = periodDurationMs,
                ),
                onDemandCap = onDemandCap,
                fetchedAt = fetchedAt,
            )
        }

        fun combinedRawJson(billingJson: String, settingsJson: String?): String {
            return buildJsonObject {
                put("authSource", AUTH_SOURCE)
                put("billing", rawElement(billingJson))
                if (!settingsJson.isNullOrBlank()) {
                    put("settings", rawElement(settingsJson))
                }
            }.toString()
        }

        private fun parsePlan(settingsJson: String?): String? {
            if (settingsJson.isNullOrBlank()) return null
            val settings = runCatching { JsonSupport.json.decodeFromString<SuperGrokSettingsResponseDto>(settingsJson) }.getOrNull()
            return settings?.subscriptionTierDisplay?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun periodDurationMillis(start: String?, end: String?): Long? {
            val startInstant = start?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val endInstant = end?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
            val duration = endInstant.toEpochMilliseconds() - startInstant.toEpochMilliseconds()
            return duration.takeIf { it > 0 }
        }

        private fun rawElement(value: String): JsonElement {
            return runCatching { JsonSupport.json.parseToJsonElement(value) }
                .getOrElse { JsonPrimitive(value) }
        }
    }
}

@Serializable
private data class SuperGrokBillingResponseDto(
    val config: SuperGrokBillingConfigDto? = null,
)

@Serializable
private data class SuperGrokBillingConfigDto(
    val monthlyLimit: SuperGrokUnitsDto? = null,
    val used: SuperGrokUnitsDto? = null,
    val onDemandCap: SuperGrokUnitsDto? = null,
    val billingPeriodStart: String? = null,
    val billingPeriodEnd: String? = null,
)

@Serializable
private data class SuperGrokUnitsDto(
    @SerialName("val") val valValue: Long? = null,
)

@Serializable
private data class SuperGrokSettingsResponseDto(
    @SerialName("subscription_tier_display") val subscriptionTierDisplay: String? = null,
)
