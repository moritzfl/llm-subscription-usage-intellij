package de.moritzf.quota.zai

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * HTTP client for fetching Z.ai GLM Coding usage quota.
 */
open class ZaiQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    @Throws(IOException::class, InterruptedException::class)
    open fun fetchQuota(apiKey: String): ZaiQuota {
        require(apiKey.isNotBlank()) { "apiKey must not be null or blank" }

        val subscriptionJson = getJson(apiKey, SUBSCRIPTION_PATH)
        val quotaJson = getJson(apiKey, QUOTA_PATH)
        val rawJson = combinedRawJson(subscriptionJson, quotaJson)

        val quota = try {
            parseQuota(subscriptionJson, quotaJson)
        } catch (exception: ZaiQuotaException) {
            throw ZaiQuotaException(exception.message ?: "Usage response invalid. Try again later.", exception.statusCode, rawJson, exception)
        } catch (exception: SerializationException) {
            throw ZaiQuotaException("Usage response invalid. Try again later.", 200, rawJson, exception)
        } catch (exception: IllegalArgumentException) {
            throw ZaiQuotaException("Usage response invalid. Try again later.", 200, rawJson, exception)
        }

        quota.fetchedAt = Clock.System.now()
        quota.rawJson = rawJson
        return quota
    }

    private fun getJson(apiKey: String, path: String): String {
        val request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw ZaiQuotaException("Usage request failed. Check your connection.", 0, null, exception)
        }

        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, body)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("Usage request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    companion object {
        @JvmField
        val DEFAULT_BASE_URI: URI = URI.create("https://api.z.ai/")

        private const val SUBSCRIPTION_PATH = "/api/biz/subscription/list"
        private const val QUOTA_PATH = "/api/monitor/usage/quota/limit"

        private fun combinedRawJson(subscriptionJson: String, quotaJson: String): String {
            return buildJsonObject {
                put("subscription", rawElement(subscriptionJson))
                put("quota", rawElement(quotaJson))
            }.toString()
        }

        private fun rawElement(json: String): JsonElement {
            return runCatching { JsonSupport.json.parseToJsonElement(json) }
                .getOrElse { JsonPrimitive(json) }
        }

        fun parseQuota(subscriptionJson: String, quotaJson: String): ZaiQuota {
            val subscriptions = JsonSupport.json.decodeFromString<ZaiSubscriptionResponseDto>(subscriptionJson)
            val quotaResponse = JsonSupport.json.decodeFromString<ZaiQuotaResponseDto>(quotaJson)
            if (isNoCodingPlanResponse(subscriptions, quotaResponse)) {
                throw ZaiQuotaException("No active Z.ai coding plan found.", quotaResponse.code ?: 200, quotaJson)
            }
            if (subscriptions.success == false || quotaResponse.success == false) {
                throw IllegalArgumentException("Z.ai response marked unsuccessful")
            }

            val subscription = subscriptions.data.firstOrNull { it.status?.equals("VALID", ignoreCase = true) == true }
                ?: subscriptions.data.firstOrNull()
            val limits = quotaResponse.data?.limits.orEmpty()
            val session = limits.firstOrNull { it.type == "TOKENS_LIMIT" && it.unit == 3 && it.number == 5 }
            val weekly = limits.firstOrNull { it.type == "TOKENS_LIMIT" && it.unit == 6 && it.number == 7 }
            val webSearch = limits.firstOrNull { it.type == "TIME_LIMIT" }
            val subscriptionReset = subscription?.nextRenewTime?.let(::parseResetDate)

            return ZaiQuota(
                plan = subscription?.productName.orEmpty(),
                sessionUsage = session?.toUsageWindow(),
                weeklyUsage = weekly?.toUsageWindow(),
                webSearchUsage = webSearch?.toCountUsageWindow(subscriptionReset ?: nextMonthStartUtc()),
            )
        }

        private fun ZaiLimitDto.toUsageWindow(): ZaiUsageWindow {
            return ZaiUsageWindow(
                usagePercent = percentage ?: percentFromValues(currentValue, usage),
                resetsAt = nextResetTime?.let(Instant::fromEpochMilliseconds),
            )
        }

        private fun ZaiLimitDto.toCountUsageWindow(resetsAt: Instant?): ZaiCountUsageWindow {
            return ZaiCountUsageWindow(
                used = currentValue ?: 0,
                limit = usage ?: 0,
                usagePercent = percentage ?: percentFromValues(currentValue, usage),
                resetsAt = resetsAt,
            )
        }

        private fun percentFromValues(used: Long?, limit: Long?): Double {
            if (used == null || limit == null || limit <= 0) return 0.0
            return used.toDouble() / limit.toDouble() * 100.0
        }

        private fun isNoCodingPlanResponse(
            subscriptions: ZaiSubscriptionResponseDto,
            quotaResponse: ZaiQuotaResponseDto,
        ): Boolean {
            val message = quotaResponse.msg.orEmpty().lowercase()
            return subscriptions.data.isEmpty() &&
                quotaResponse.success == false &&
                (message.contains("coding plan") || message.contains("不存在"))
        }

        private fun parseResetDate(value: String): Instant? {
            return runCatching {
                val date = LocalDate.parse(value.trim())
                Instant.fromEpochMilliseconds(date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
            }.getOrNull()
        }

        private fun nextMonthStartUtc(): Instant {
            val nextMonth = java.time.Instant.now().atZone(ZoneOffset.UTC).toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
            return Instant.fromEpochMilliseconds(nextMonth.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        }
    }
}

@Serializable
private data class ZaiSubscriptionResponseDto(
    val code: Int? = null,
    val msg: String? = null,
    val data: List<ZaiSubscriptionDto> = emptyList(),
    val success: Boolean? = null,
)

@Serializable
private data class ZaiSubscriptionDto(
    val productName: String? = null,
    val status: String? = null,
    val nextRenewTime: String? = null,
)

@Serializable
private data class ZaiQuotaResponseDto(
    val code: Int? = null,
    val msg: String? = null,
    val data: ZaiQuotaDataDto? = null,
    val success: Boolean? = null,
)

@Serializable
private data class ZaiQuotaDataDto(
    val limits: List<ZaiLimitDto> = emptyList(),
)

@Serializable
private data class ZaiLimitDto(
    val type: String? = null,
    val unit: Int? = null,
    val number: Int? = null,
    val usage: Long? = null,
    val currentValue: Long? = null,
    val remaining: Long? = null,
    val percentage: Double? = null,
    val nextResetTime: Long? = null,
)
