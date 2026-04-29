package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Duration.Companion.seconds

open class MiniMaxQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    open fun fetchQuota(apiKey: String, region: MiniMaxRegion): MiniMaxQuota {
        require(apiKey.isNotBlank()) { "apiKey must not be null or blank" }

        var lastException: MiniMaxQuotaException? = null
        for (endpoint in endpointsFor(region)) {
            try {
                val body = getJson(apiKey, endpoint)
                val quota = parseQuota(body, region)
                quota.fetchedAt = Clock.System.now()
                quota.rawJson = body
                return quota
            } catch (exception: MiniMaxQuotaException) {
                lastException = exception
                if (exception.statusCode == 401 || exception.statusCode == 403) throw exception
            }
        }
        throw lastException ?: MiniMaxQuotaException("Request failed. Check your connection.")
    }

    private fun getJson(apiKey: String, endpoint: URI): String {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        }

        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException("Session expired. Check your MiniMax API key.", status, body)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    companion object {
        private val GLOBAL_ENDPOINTS = listOf(
            URI.create("https://api.minimax.io/v1/api/openplatform/coding_plan/remains"),
            URI.create("https://api.minimax.io/v1/coding_plan/remains"),
            URI.create("https://www.minimax.io/v1/api/openplatform/coding_plan/remains"),
        )
        private val CN_ENDPOINTS = listOf(
            URI.create("https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains"),
            URI.create("https://api.minimaxi.com/v1/coding_plan/remains"),
        )

        private fun endpointsFor(region: MiniMaxRegion): List<URI> {
            return if (region == MiniMaxRegion.CN) CN_ENDPOINTS else GLOBAL_ENDPOINTS
        }

        fun parseQuota(body: String, region: MiniMaxRegion): MiniMaxQuota {
            val dto = try {
                JsonSupport.json.decodeFromString<MiniMaxResponseDto>(body)
            } catch (exception: Exception) {
                throw MiniMaxQuotaException("Could not parse usage data.", 200, body, exception)
            }

            val statusCode = dto.baseResp?.statusCode ?: 0
            val statusMessage = dto.baseResp?.statusMsg.orEmpty()
            if (statusCode != 0) {
                if (statusCode == 401 || statusCode == 403 || statusMessage.contains("auth", ignoreCase = true)) {
                    throw MiniMaxQuotaException("Session expired. Check your MiniMax API key.", statusCode, body)
                }
                throw MiniMaxQuotaException("MiniMax API error: ${statusMessage.ifBlank { statusCode.toString() }}", statusCode, body)
            }

            val item = dto.modelRemains.firstOrNull()
                ?: throw MiniMaxQuotaException("Could not parse usage data.", 200, body)
            val total = item.currentIntervalTotalCount ?: 0
            val explicitUsed = item.currentIntervalUsedCount
            val remaining = item.currentIntervalUsageCount ?: item.currentIntervalRemainingCount ?: item.currentIntervalRemainsCount
            val used = when {
                explicitUsed != null -> explicitUsed
                remaining != null -> (total - remaining).coerceAtLeast(0)
                else -> 0
            }
            val percent = if (total > 0) used.toDouble() / total.toDouble() * 100.0 else 0.0
            val resetsAt = item.endTime?.let(::epochSecondsOrMillis) ?: item.remainsTime?.let { Clock.System.now().plus(it.seconds) }
            val startMs = item.startTime?.let(::epochSecondsOrMillis)?.toEpochMilliseconds()
            val endMs = item.endTime?.let(::epochSecondsOrMillis)?.toEpochMilliseconds()

            return MiniMaxQuota(
                plan = normalizePlan(item.currentSubscribeTitle ?: item.planName ?: item.plan ?: inferPlan(total, region), region),
                region = region,
                sessionUsage = MiniMaxUsageWindow(
                    used = used,
                    limit = total,
                    usagePercent = percent,
                    resetsAt = resetsAt,
                    periodDurationMs = if (startMs != null && endMs != null && endMs > startMs) endMs - startMs else null,
                ),
            )
        }

        private fun epochSecondsOrMillis(value: Long): Instant {
            val millis = if (value < 10_000_000_000L) value * 1000 else value
            return Instant.fromEpochMilliseconds(millis)
        }

        private fun normalizePlan(plan: String, region: MiniMaxRegion): String {
            val base = plan.trim().ifBlank { "MiniMax Coding Plan" }
            val suffix = " (${region})"
            return if (base.endsWith(suffix)) base else base + suffix
        }

        private fun inferPlan(limit: Long, region: MiniMaxRegion): String {
            return when (region) {
                MiniMaxRegion.GLOBAL -> when (limit) {
                    100L, 1500L -> "MiniMax Coding Lite"
                    300L, 4500L -> "MiniMax Coding Pro"
                    1000L, 15000L -> "MiniMax Coding Max"
                    2000L, 30000L -> "MiniMax Coding Ultra"
                    else -> "MiniMax Coding Plan"
                }
                MiniMaxRegion.CN -> when (limit) {
                    600L -> "MiniMax Coding Lite"
                    1500L -> "MiniMax Coding Pro"
                    4500L -> "MiniMax Coding Max"
                    else -> "MiniMax Coding Plan"
                }
            }
        }
    }
}

@Serializable
private data class MiniMaxResponseDto(
    @SerialName("base_resp") val baseResp: MiniMaxBaseRespDto? = null,
    @SerialName("model_remains") val modelRemains: List<MiniMaxRemainDto> = emptyList(),
)

@Serializable
private data class MiniMaxBaseRespDto(
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("status_msg") val statusMsg: String? = null,
)

@Serializable
private data class MiniMaxRemainDto(
    @SerialName("current_interval_total_count") val currentIntervalTotalCount: Long? = null,
    @SerialName("current_interval_usage_count") val currentIntervalUsageCount: Long? = null,
    @SerialName("current_interval_used_count") val currentIntervalUsedCount: Long? = null,
    @SerialName("current_interval_remaining_count") val currentIntervalRemainingCount: Long? = null,
    @SerialName("current_interval_remains_count") val currentIntervalRemainsCount: Long? = null,
    @SerialName("start_time") val startTime: Long? = null,
    @SerialName("end_time") val endTime: Long? = null,
    @SerialName("remains_time") val remainsTime: Long? = null,
    @SerialName("current_subscribe_title") val currentSubscribeTitle: String? = null,
    @SerialName("plan_name") val planName: String? = null,
    val plan: String? = null,
)
