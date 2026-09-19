package de.moritzf.quota.ollama

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import kotlin.time.Clock
import kotlin.time.Instant
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
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * HTTP client for Ollama Cloud subscription usage via the official API key endpoint.
 *
 * `GET https://ollama.com/api/usage` with `Authorization: Bearer <api-key>`.
 * See https://github.com/ollama/ollama/issues/12532 and https://docs.ollama.com/api/authentication
 */
open class OllamaQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    open fun fetchQuota(apiKey: String): OllamaQuota {
        val token = apiKey.trim().takeIf { it.isNotBlank() }
            ?: throw OllamaQuotaException("Ollama API key missing. Add an Ollama API key in settings.")

        val body = getUsageJson(token)
        val quota = try {
            parseQuota(body)
        } catch (exception: OllamaQuotaException) {
            throw exception
        } catch (exception: Exception) {
            throw OllamaQuotaException("Ollama usage response changed.", 200, body, exception)
        }
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = buildRawResponse(
            body,
            quota.sessionUsage?.resetsAt,
            quota.weeklyUsage?.resetsAt,
            quota.monthlyUsage?.resetsAt,
        )
        return quota
    }

    private fun getUsageJson(apiKey: String): String {
        val request = HttpRequest.newBuilder()
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
            throw OllamaQuotaException("Ollama usage API rate limited. Try again later.", status, body)
        }
        if (status !in 200..299) {
            throw OllamaQuotaException(
                "Ollama usage request failed (HTTP $status). Try again later.",
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
            throw OllamaQuotaException("Ollama usage request timed out. Try again later.", 0, null, exception)
        } catch (exception: IOException) {
            throw OllamaQuotaException("Ollama usage request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OllamaQuotaException("Ollama usage request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://ollama.com/api/usage")

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
            return runCatching { JsonSupport.json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
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
            updated.rawJson = buildRawResponse(
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
            val root = runCatching { JsonSupport.json.parseToJsonElement(usageJson) }.getOrNull() as? JsonObject
                ?: throw OllamaQuotaException("Ollama usage response changed.", 200, usageJson)

            val limits = root["limits"] as? JsonObject

            fun window(key: String): OllamaUsageWindow? =
                JsonSupport.decodeSectionOrNull(limits?.get(key), OllamaLimitWindowDto.serializer())?.toWindow()

            val sessionUsage = window("session")?.withDefaultReset(OllamaResetSchedule.sessionResetsAt(now))
            val weeklyUsage = window("weekly")?.withDefaultReset(OllamaResetSchedule.weeklyResetsAt(now))
            val activityPeriod = JsonSupport.decodeSectionOrNull(
                (root["activity"] as? JsonObject)?.get("period"),
                OllamaActivityPeriodDto.serializer(),
            )
            val monthlyUsage = window("monthly")?.withMonthlyPeriod(activityPeriod, now)
            if (sessionUsage == null && weeklyUsage == null && monthlyUsage == null) {
                throw OllamaQuotaException("Ollama usage response changed.", 200, usageJson)
            }

            return OllamaQuota(
                sessionUsage = sessionUsage,
                weeklyUsage = weeklyUsage,
                monthlyUsage = monthlyUsage,
            )
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
            runCatching { Instant.parse(value) }.getOrNull()?.let { return it }
            val javaInstant = runCatching { java.time.Instant.parse(value) }.getOrNull() ?: return null
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
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val usage: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)

@Serializable
private data class OllamaActivityPeriodDto(
    val type: String? = null,
    @SerialName("starting_at") val startingAt: String? = null,
    @SerialName("ending_at") val endingAt: String? = null,
)
