package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
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
            throw SuperGrokQuotaException(
                exception.message ?: "Grok billing response changed.",
                200,
                rawJson,
                exception
            )
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
                throw SuperGrokQuotaException(
                    "Grok auth expired. Log in to SuperGrok again from settings.",
                    status,
                    body
                )
            }
            if (status !in 200..299) {
                if (!required) return null
                if (isGrokBillingTimeout(status, body)) {
                    if (attempt < BILLING_REQUEST_ATTEMPTS) continue
                    throw SuperGrokQuotaException(GROK_BILLING_TIMEOUT_MESSAGE, status)
                }
                throw SuperGrokQuotaException(
                    "Grok billing request failed (HTTP $status). Try again later.",
                    status,
                    body
                )
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
            val config = billingConfig(weeklyBillingJson)
            val used = config.unitValue("used") ?: 0L
            val limit = config.unitValue("monthlyLimit") ?: 0L
            val usagePercent = resolveUsagePercent(config, used, limit)
            val period = config.objectValue("currentPeriod")
            val periodStart = config.stringValue("billingPeriodStart") ?: period?.stringValue("start")
            val periodEnd = config.stringValue("billingPeriodEnd") ?: period?.stringValue("end")
            val resetsAt = periodEnd?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val periodDurationMs = periodDurationMillis(periodStart, periodEnd)
            val plan = parsePlan(settingsJson)
            val isUnified = config.booleanValue("isUnifiedBillingUser") == true
            val periodType = period?.stringValue("type")
            val onDemandCap = config.unitValue("onDemandCap") ?: 0L

            return SuperGrokQuota(
                plan = plan.orEmpty(),
                authSource = AUTH_SOURCE,
                creditUsage = usagePercent?.let {
                    SuperGrokUsageWindow(
                        label = if (isUnified) "Weekly credits" else "Credits used",
                        used = used,
                        limit = limit,
                        usagePercent = it.coerceIn(0.0, 100.0),
                        resetsAt = resetsAt,
                        periodDurationMs = periodDurationMs,
                    )
                },
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
            return (runCatching { JsonSupport.json.parseToJsonElement(settingsJson) }.getOrNull() as? JsonObject)
                ?.stringValue("subscription_tier_display")?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun resolveUsagePercent(config: JsonObject, used: Long, limit: Long): Double? {
            config.doubleValue("creditUsagePercent")?.let { return it }
            (config["productUsage"] as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.doubleValue("usagePercent") }
                ?.maxOrNull()?.let { return it }
            if (limit > 0) {
                return used.toDouble() / limit.toDouble() * 100.0
            }
            return null
        }

        private fun billingConfig(json: String): JsonObject {
            val root = runCatching { JsonSupport.json.parseToJsonElement(json) as? JsonObject }
                .getOrElse { throw SuperGrokQuotaException("Grok billing response changed.", 200, json, it) }
                ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, json)
            return root.objectValue("config") ?: root.objectValue("billing")?.objectValue("config")
            ?: throw SuperGrokQuotaException("Grok billing response changed.", 200, json)
        }

        private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject

        private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.booleanValue(name: String): Boolean? = (this[name] as? JsonPrimitive)?.booleanOrNull

        private fun JsonObject.doubleValue(name: String): Double? {
            return (this[name] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        }

        private fun JsonObject.unitValue(name: String): Long? {
            val value = this[name]
            return (value as? JsonPrimitive)?.longOrNull
                ?: ((value as? JsonObject)?.get("val") as? JsonPrimitive)?.longOrNull
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
