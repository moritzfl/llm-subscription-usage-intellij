package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

open class SuperGrokQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    open fun fetchQuota(accessToken: String?): SuperGrokQuota {
        val token = accessToken?.trim()?.takeIf { it.isNotBlank() }
            ?: throw SuperGrokQuotaException("Grok login required. Log in from SuperGrok settings.")

        val weeklyJson = getJson(token, WEEKLY_BILLING_PATH, required = true)
            ?: throw SuperGrokQuotaException("Grok billing response changed.")
        val settingsJson = getJson(token, SETTINGS_PATH, required = false)

        val rawJson = combinedRawJson(weeklyJson, settingsJson)

        val quota = try {
            parseQuota(weeklyJson, settingsJson)
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

        for (attempt in 1..BILLING_REQUEST_ATTEMPTS) {
            val response = send(request)
            val status = response.statusCode()
            val body = response.body()
            if (status == 401 || status == 403) {
                throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status, body)
            }
            if (status !in 200..299) {
                if (!required) return null
                if (isGrokBillingTimeout(status, body)) {
                    if (attempt < BILLING_REQUEST_ATTEMPTS) continue
                    throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE, status)
                }
                throw SuperGrokQuotaException("Grok billing request failed (HTTP $status). Try again later.", status, body)
            }
            return body
        }
        throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE)
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE, 0, null, exception)
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

        private const val WEEKLY_BILLING_PATH = "billing?format=credits"
        private const val SETTINGS_PATH = "settings"
        private const val TOKEN_AUTH_HEADER = "xai-grok-cli"
        private const val AUTH_SOURCE = "xai-oauth-cli-proxy"
        private const val BILLING_REQUEST_ATTEMPTS = 2
        private const val GROK_BILLING_TIMEOUT_MESSAGE =
            "Grok billing request timed out. The Grok billing API cancelled the request before returning usage data; try again later."

        fun parseQuota(
            weeklyBillingJson: String,
            settingsJson: String? = null,
            fetchedAt: Instant = Clock.System.now(),
        ): SuperGrokQuota {
            val billing = runCatching { JsonSupport.json.decodeFromString<SuperGrokBillingResponseDto>(weeklyBillingJson) }
                .getOrElse { throw SuperGrokQuotaException("Grok billing response changed.", 200, weeklyBillingJson, it) }
            val config = billing.config ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, weeklyBillingJson)

            val usagePercent = config.creditUsagePercent
                ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, weeklyBillingJson)
            val resetsAt = config.billingPeriodEnd?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, weeklyBillingJson)
            val periodDurationMs = periodDurationMillis(config.billingPeriodStart, config.billingPeriodEnd)
        val plan = parsePlan(settingsJson)
            val isUnified = config.isUnifiedBillingUser == true
            val periodType = config.currentPeriod?.type
            val onDemandCap = config.onDemandCap?.valValue ?: 0L

            return SuperGrokQuota(
                plan = plan.orEmpty(),
                authSource = AUTH_SOURCE,
                creditUsage = SuperGrokUsageWindow(
                    label = if (isUnified) "Weekly credits" else "Credits used",
                    used = 0,
                    limit = 0,
                    usagePercent = usagePercent.coerceIn(0.0, 100.0),
                    resetsAt = resetsAt,
                    periodDurationMs = periodDurationMs,
                ),
                onDemandCap = onDemandCap,
                isUnifiedBilling = isUnified,
                periodType = periodType.orEmpty(),
                fetchedAt = fetchedAt,
            )
        }

        fun combinedRawJson(weeklyBillingJson: String, settingsJson: String?): String {
            return buildJsonObject {
                put("authSource", AUTH_SOURCE)
                put("billing", rawElement(weeklyBillingJson))
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

        private fun isGrokBillingTimeout(status: Int, body: String): Boolean {
            if (status != 400) return false
            val payload = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return false
            val code = (payload["code"] as? JsonPrimitive)?.contentOrNull
            val error = (payload["error"] as? JsonPrimitive)?.contentOrNull
            return code.equals("The operation was cancelled", ignoreCase = true) &&
                error.equals("Timeout expired", ignoreCase = true)
        }
    }
}

@Serializable
private data class SuperGrokBillingResponseDto(
    val config: SuperGrokBillingConfigDto? = null,
)

@Serializable
private data class SuperGrokBillingConfigDto(
    @SerialName("creditUsagePercent") val creditUsagePercent: Double? = null,
    @SerialName("onDemandCap") val onDemandCap: SuperGrokUnitsDto? = null,
    @SerialName("onDemandUsed") val onDemandUsed: SuperGrokUnitsDto? = null,
    @SerialName("billingPeriodStart") val billingPeriodStart: String? = null,
    @SerialName("billingPeriodEnd") val billingPeriodEnd: String? = null,
    @SerialName("isUnifiedBillingUser") val isUnifiedBillingUser: Boolean? = null,
    @SerialName("currentPeriod") val currentPeriod: SuperGrokCurrentPeriodDto? = null,
    @SerialName("productUsage") val productUsage: List<SuperGrokProductUsageDto>? = null,
    // Legacy monthly fields (kept for backward compatibility with non-unified accounts)
    @SerialName("monthlyLimit") val monthlyLimit: SuperGrokUnitsDto? = null,
    @SerialName("used") val used: SuperGrokUnitsDto? = null,
)

@Serializable
private data class SuperGrokCurrentPeriodDto(
    val type: String? = null,
    val start: String? = null,
    val end: String? = null,
)

@Serializable
private data class SuperGrokProductUsageDto(
    val product: String? = null,
    @SerialName("usagePercent") val usagePercent: Double? = null,
)

@Serializable
private data class SuperGrokUnitsDto(
    @SerialName("val") val valValue: Long? = null,
)

@Serializable
private data class SuperGrokSettingsResponseDto(
    @SerialName("subscription_tier_display") val subscriptionTierDisplay: String? = null,
)