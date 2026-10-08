package de.moritzf.quota.ollama

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * HTTP client for Ollama Cloud subscription usage via the official API key endpoint.
 *
 * `GET https://ollama.com/api/balance` with `Authorization: Bearer <api-key>`. See
 * https://docs.ollama.com/api/balance. `/api/usage` now reports activity history, not quota.
 */
open class OllamaQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    open fun fetchQuota(apiKey: String): OllamaQuota {
        val token =
            apiKey.trim().takeIf { it.isNotBlank() }
                ?: throw OllamaQuotaException(
                    "Ollama API key missing. Add an Ollama API key in settings."
                )

        val body = getBalanceJson(token)
        val quota =
            try {
                parseQuota(body)
            } catch (exception: OllamaQuotaException) {
                throw exception
            } catch (exception: Exception) {
                throw OllamaQuotaException("Ollama balance response changed.", 200, body, exception)
            }
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return quota
    }

    private fun getBalanceJson(apiKey: String): String {
        val request =
            HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .GET()
                .build()

        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw OllamaQuotaException(
                "Ollama API key invalid. Check your Ollama API key in settings.",
                status,
                body,
            )
        }
        if (status == 429) {
            throw OllamaQuotaException(
                "Ollama balance API rate limited. Try again later.",
                status,
                body,
            )
        }
        if (status !in 200..299) {
            throw OllamaQuotaException(
                "Ollama balance request failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw OllamaQuotaException(
                "Ollama balance request timed out. Try again later.",
                0,
                null,
                exception,
            )
        } catch (exception: IOException) {
            throw OllamaQuotaException(
                "Ollama balance request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OllamaQuotaException(
                "Ollama balance request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }
    }

    companion object {
        @JvmField val DEFAULT_ENDPOINT: URI = URI.create("https://ollama.com/api/balance")

        internal fun buildRawResponse(
            usageBody: String,
            sessionResetsAt: Instant?,
            weeklyResetsAt: Instant?,
            monthlyResetsAt: Instant? = null,
        ): String {
            val usage = jsonOrRaw(usageBody)
            val resets = buildJsonObject {
                sessionResetsAt?.let { put("session", it.toString()) }
                weeklyResetsAt?.let { put("weekly", it.toString()) }
                monthlyResetsAt?.let { put("monthly", it.toString()) }
            }
            return JsonSupport.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    when (usage) {
                        is JsonObject -> usage.forEach { (key, value) -> put(key, value) }
                        null -> Unit
                        else -> put("usage", usage)
                    }
                    if (resets.isNotEmpty()) {
                        put("resets_at", resets)
                    }
                },
            )
        }

        private fun jsonOrRaw(body: String): JsonElement? {
            val value = body.trim().takeIf { it.isNotEmpty() } ?: return null
            return runCatching { JsonSupport.json.parseToJsonElement(value) }
                .getOrElse { JsonPrimitive(value) }
        }

        fun applyConfiguredMonthlyReset(
            quota: OllamaQuota,
            anchor: Instant?,
            now: Instant = Clock.System.now(),
        ): OllamaQuota {
            val monthly = quota.monthlyUsage ?: return quota
            if (monthly.resetsAt != null || anchor == null) return quota
            val next = OllamaResetSchedule.monthlyResetsAt(anchor, now)
            val updated = quota.copy(monthlyUsage = monthly.copy(resetsAt = next))
            updated.fetchedAt = quota.fetchedAt
            updated.rawJson =
                buildRawResponse(
                    quota.rawJson ?: "{}",
                    updated.sessionUsage?.resetsAt,
                    updated.weeklyUsage?.resetsAt,
                    next,
                )
            return updated
        }

        fun parseQuota(usageJson: String, now: Instant = Clock.System.now()): OllamaQuota {
            // Each limit window is decoded on its own so one reshaped or unparsable block (for
            // example a changed session entry, per-model details, activity/cost extras, or unknown
            // attributes) only drops that block instead of hiding the whole quota.
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(usageJson) }.getOrNull()
                    as? JsonObject
                    ?: throw OllamaQuotaException(
                        "Ollama balance response changed.",
                        200,
                        usageJson,
                    )

            if (root["included"] is JsonObject) {
                return parseBalance(root["included"] as JsonObject, usageJson)
            }

            // Retain support for the former /api/usage limit format.
            val limits = root["limits"] as? JsonObject

            fun window(key: String): OllamaUsageWindow? =
                JsonSupport.decodeSectionOrNull(limits?.get(key), OllamaLimitWindowDto.serializer())
                    ?.toWindow()

            val sessionUsage =
                window("session")?.withDefaultReset(OllamaResetSchedule.sessionResetsAt(now))
            val weeklyUsage =
                window("weekly")?.withDefaultReset(OllamaResetSchedule.weeklyResetsAt(now))
            val activityPeriod =
                JsonSupport.decodeSectionOrNull(
                    (root["activity"] as? JsonObject)?.get("period"),
                    OllamaActivityPeriodDto.serializer(),
                )
            val monthlyUsage = window("monthly")?.withMonthlyPeriod(activityPeriod, now)
            if (sessionUsage == null && weeklyUsage == null && monthlyUsage == null) {
                throw OllamaQuotaException("Ollama balance response changed.", 200, usageJson)
            }

            return OllamaQuota(
                sessionUsage = sessionUsage,
                weeklyUsage = weeklyUsage,
                monthlyUsage = monthlyUsage,
            )
        }

        private fun parseBalance(included: JsonObject, body: String): OllamaQuota {
            fun window(key: String): OllamaUsageWindow? {
                val limit =
                    JsonSupport.decodeSectionOrNull(
                        included[key],
                        OllamaBalanceLimitDto.serializer(),
                    ) ?: return null
                val remaining = limit.remainingPercent?.takeIf { it.isFinite() } ?: return null
                return OllamaUsageWindow(
                    usagePercent = (100.0 - remaining).coerceIn(0.0, 100.0),
                    resetsAt = parseInstant(limit.resetsAt),
                )
            }

            val credits =
                JsonSupport.decodeSectionOrNull(included, OllamaIncludedCreditsDto.serializer())
            val allowance = credits?.allowanceUsd?.takeIf { it.isFinite() && it > 0.0 }
            val balance = credits?.balanceUsd?.takeIf { it.isFinite() }
            val monthlyUsage =
                if (allowance != null && balance != null) {
                    val period =
                        JsonSupport.decodeSectionOrNull(
                            included["period"],
                            OllamaBalancePeriodDto.serializer(),
                        )
                    val used = (allowance - balance).coerceAtLeast(0.0)
                    OllamaUsageWindow(
                        usagePercent = (used / allowance * 100.0).coerceIn(0.0, 100.0),
                        resetsAt = parseInstant(period?.until),
                        periodStartedAt = parseInstant(period?.from),
                        usedAmountUsd = used,
                        allowanceUsd = allowance,
                    )
                } else null
            val quota =
                OllamaQuota(
                    sessionUsage = window("session"),
                    weeklyUsage = window("weekly"),
                    monthlyUsage = monthlyUsage,
                )
            if (!quota.hasUsageState()) {
                throw OllamaQuotaException("Ollama balance response changed.", 200, body)
            }
            return quota
        }

        private fun OllamaUsageWindow.withDefaultReset(defaultReset: Instant): OllamaUsageWindow =
            if (resetsAt != null) this else copy(resetsAt = defaultReset)

        private fun OllamaUsageWindow.withMonthlyPeriod(
            period: OllamaActivityPeriodDto?,
            now: Instant,
        ): OllamaUsageWindow {
            if (resetsAt != null) return this
            val endedAt = parseInstant(period?.endingAt) ?: return this
            // last_4_weeks.ending_at is the as-of time (now), not billing reset.
            if (endedAt.toEpochMilliseconds() - now.toEpochMilliseconds() <= FUTURE_END_SLACK_MS) {
                return this
            }
            return copy(resetsAt = endedAt)
        }

        private fun parseInstant(raw: String?): Instant? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            runCatching { Instant.parse(value) }
                .getOrNull()
                ?.let {
                    return it
                }
            val javaInstant =
                runCatching { java.time.Instant.parse(value) }.getOrNull() ?: return null
            return Instant.fromEpochSeconds(javaInstant.epochSecond, javaInstant.nano.toLong())
        }

        private fun OllamaLimitWindowDto.toWindow(): OllamaUsageWindow? {
            val usage = usage ?: return null
            // Usage arrives as a 0..1 fraction (0.046 = 4.6%). Anything above 1 is treated as an
            // already-percent value so a unit change upstream still shows a sane number.
            val percent = if (usage <= 1.0) usage * 100.0 else usage
            return OllamaUsageWindow(
                usagePercent = percent.coerceIn(0.0, 100.0),
                resetsAt = parseInstant(resetsAt),
            )
        }

        private const val FUTURE_END_SLACK_MS = 60L * 60L * 1000L
    }
}

@Serializable
private data class OllamaLimitWindowDto(
    @Serializable(with = LenientDoubleOrNullSerializer::class) val usage: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)

@Serializable
private data class OllamaActivityPeriodDto(
    val type: String? = null,
    @SerialName("starting_at") val startingAt: String? = null,
    @SerialName("ending_at") val endingAt: String? = null,
)

@Serializable
private data class OllamaIncludedCreditsDto(
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    @SerialName("balance_usd")
    val balanceUsd: Double? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    @SerialName("allowance_usd")
    val allowanceUsd: Double? = null,
)

@Serializable
private data class OllamaBalancePeriodDto(
    val from: String? = null,
    val until: String? = null,
)

@Serializable
private data class OllamaBalanceLimitDto(
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    @SerialName("remaining_percent")
    val remainingPercent: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)
