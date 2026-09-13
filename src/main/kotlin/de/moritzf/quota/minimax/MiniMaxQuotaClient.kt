package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class MiniMaxQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpointsByRegion: (MiniMaxRegion) -> List<URI> = Companion::endpointsFor,
) {
    open fun fetchQuota(apiKey: String, region: MiniMaxRegion): MiniMaxQuota {
        require(apiKey.isNotBlank()) { "apiKey must not be null or blank" }

        var lastException: MiniMaxQuotaException? = null
        for (endpoint in endpointsByRegion(region)) {
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
            throw MiniMaxQuotaException("Session expired. Check your MiniMax subscription key.", status, body)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    companion object {
        private const val STATUS_EXHAUSTED = 2
        private const val STATUS_UNLIMITED = 3
        private const val MILLIS_EPOCH_THRESHOLD = 10_000_000_000L

        private val GLOBAL_ENDPOINTS = listOf(
            URI.create("https://api.minimax.io/v1/token_plan/remains"),
            URI.create("https://www.minimax.io/v1/token_plan/remains"),
            URI.create("https://api.minimax.io/v1/api/openplatform/coding_plan/remains"),
            URI.create("https://api.minimax.io/v1/coding_plan/remains"),
            URI.create("https://www.minimax.io/v1/api/openplatform/coding_plan/remains"),
        )
        private val CN_ENDPOINTS = listOf(
            URI.create("https://api.minimaxi.com/v1/token_plan/remains"),
            URI.create("https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains"),
            URI.create("https://api.minimaxi.com/v1/coding_plan/remains"),
        )

        internal fun endpointsFor(region: MiniMaxRegion): List<URI> {
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
                    throw MiniMaxQuotaException("Session expired. Check your MiniMax subscription key.", statusCode, body)
                }
                throw MiniMaxQuotaException("MiniMax API error: ${statusMessage.ifBlank { statusCode.toString() }}", statusCode, body)
            }

            val item = dto.modelRemains.firstOrNull { it.modelName.equals("general", ignoreCase = true) }
                ?: dto.modelRemains.firstOrNull()
                ?: throw MiniMaxQuotaException("Could not parse usage data.", 200, body)
            val countdownIsMillis = countdownUsesMillis(item)

            return MiniMaxQuota(
                plan = normalizePlan(inferPlan(item, region), region),
                region = region,
                sessionUsage = parseWindow(
                    total = item.currentIntervalTotalCount,
                    explicitUsed = item.currentIntervalUsedCount,
                    usageCount = item.currentIntervalUsageCount,
                    remainingCount = item.currentIntervalRemainingCount,
                    remainsCount = item.currentIntervalRemainsCount,
                    remainingPercent = item.currentIntervalRemainingPercent,
                    status = item.currentIntervalStatus,
                    startTime = item.startTime,
                    endTime = item.endTime,
                    remainsTime = item.remainsTime,
                    countdownIsMillis = countdownIsMillis,
                    required = true,
                ),
                weeklyUsage = parseWindow(
                    total = item.currentWeeklyTotalCount,
                    explicitUsed = item.currentWeeklyUsedCount,
                    usageCount = item.currentWeeklyUsageCount,
                    remainingCount = item.currentWeeklyRemainingCount,
                    remainsCount = item.currentWeeklyRemainsCount,
                    remainingPercent = item.currentWeeklyRemainingPercent,
                    status = item.currentWeeklyStatus,
                    startTime = item.weeklyStartTime,
                    endTime = item.weeklyEndTime,
                    remainsTime = item.weeklyRemainsTime,
                    countdownIsMillis = countdownIsMillis,
                    required = false,
                ),
            )
        }

        private fun parseWindow(
            total: Long?,
            explicitUsed: Long?,
            usageCount: Long?,
            remainingCount: Long?,
            remainsCount: Long?,
            remainingPercent: Double?,
            status: Int?,
            startTime: Long?,
            endTime: Long?,
            remainsTime: Long?,
            countdownIsMillis: Boolean,
            required: Boolean,
        ): MiniMaxUsageWindow? {
            if (status == STATUS_UNLIMITED) return null
            val hasSignal = remainingPercent != null ||
                status != null ||
                startTime != null ||
                endTime != null ||
                remainsTime != null ||
                (total ?: 0L) > 0L ||
                explicitUsed != null ||
                usageCount != null ||
                remainingCount != null ||
                remainsCount != null
            if (!required && !hasSignal) return null

            val resetsAt = endTime?.let(::epochSecondsOrMillis)
                ?: remainsTime?.let { countdownInstant(it, countdownIsMillis) }
            val startMs = startTime?.let { epochSecondsOrMillis(it).toEpochMilliseconds() }
            val endMs = endTime?.let { epochSecondsOrMillis(it).toEpochMilliseconds() }
            val periodDurationMs = if (startMs != null && endMs != null && endMs > startMs) endMs - startMs else null

            val remaining = remainingCount ?: remainsCount ?: usageCount
            val usedFromCounts = when {
                explicitUsed != null -> explicitUsed
                remaining != null && (total ?: 0L) > 0L -> (total!! - remaining).coerceAtLeast(0)
                else -> null
            }
            val percentFromCounts = if (total != null && total > 0 && usedFromCounts != null) {
                usedFromCounts.toDouble() / total.toDouble() * 100.0
            } else {
                null
            }
            val percentFromRemaining = remainingPercent?.let { (100.0 - it).coerceAtLeast(0.0) }
            val usagePercent = when {
                status == STATUS_EXHAUSTED -> 100.0
                percentFromRemaining != null -> percentFromRemaining
                percentFromCounts != null -> percentFromCounts
                else -> 0.0
            }

            return MiniMaxUsageWindow(
                used = usedFromCounts ?: 0,
                limit = total ?: 0,
                usagePercent = usagePercent,
                resetsAt = resetsAt,
                periodDurationMs = periodDurationMs,
            )
        }

        private fun countdownUsesMillis(item: MiniMaxRemainDto): Boolean {
            return timesAreMillis(item.startTime, item.endTime) ||
                timesAreMillis(item.weeklyStartTime, item.weeklyEndTime) ||
                looksLikeMillisCountdown(item.remainsTime) ||
                looksLikeMillisCountdown(item.weeklyRemainsTime)
        }

        private fun timesAreMillis(startTime: Long?, endTime: Long?): Boolean {
            val sample = endTime ?: startTime ?: return false
            return sample >= MILLIS_EPOCH_THRESHOLD
        }

        private fun looksLikeMillisCountdown(value: Long?): Boolean {
            return value != null && value > 604_800L
        }

        private fun countdownInstant(value: Long, millisTimes: Boolean): Instant {
            val duration = if (millisTimes) value.milliseconds else value.seconds
            return Clock.System.now().plus(duration)
        }

        private fun epochSecondsOrMillis(value: Long): Instant {
            val millis = if (value < MILLIS_EPOCH_THRESHOLD) value * 1000 else value
            return Instant.fromEpochMilliseconds(millis)
        }

        private fun normalizePlan(plan: String, region: MiniMaxRegion): String {
            val base = plan.trim().ifBlank { "MiniMax Token Plan" }
            val suffix = " (${region})"
            return if (base.endsWith(suffix)) base else base + suffix
        }

        private fun inferPlan(item: MiniMaxRemainDto, region: MiniMaxRegion): String {
            val titled = item.currentSubscribeTitle ?: item.planName ?: item.plan
            if (!titled.isNullOrBlank()) return titled.trim()
            val weekly = item.currentWeeklyRemainingPercent != null ||
                item.currentWeeklyStatus != null ||
                item.weeklyEndTime != null ||
                item.weeklyRemainsTime != null ||
                (item.currentWeeklyTotalCount ?: 0L) > 0L
            if (weekly || item.currentIntervalRemainingPercent != null) {
                return "MiniMax Token Plan"
            }
            val total = item.currentIntervalTotalCount ?: 0
            return when (region) {
                MiniMaxRegion.GLOBAL -> when (total) {
                    100L, 1500L -> "MiniMax Coding Lite"
                    300L, 4500L -> "MiniMax Coding Pro"
                    1000L, 15000L -> "MiniMax Coding Max"
                    2000L, 30000L -> "MiniMax Coding Ultra"
                    else -> "MiniMax Coding Plan"
                }
                MiniMaxRegion.CN -> when (total) {
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
    @SerialName("model_name") val modelName: String? = null,
    @SerialName("current_interval_total_count") val currentIntervalTotalCount: Long? = null,
    @SerialName("current_interval_usage_count") val currentIntervalUsageCount: Long? = null,
    @SerialName("current_interval_used_count") val currentIntervalUsedCount: Long? = null,
    @SerialName("current_interval_remaining_count") val currentIntervalRemainingCount: Long? = null,
    @SerialName("current_interval_remains_count") val currentIntervalRemainsCount: Long? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    @SerialName("current_interval_remaining_percent") val currentIntervalRemainingPercent: Double? = null,
    @SerialName("current_interval_status") val currentIntervalStatus: Int? = null,
    @SerialName("start_time") val startTime: Long? = null,
    @SerialName("end_time") val endTime: Long? = null,
    @SerialName("remains_time") val remainsTime: Long? = null,
    @SerialName("current_weekly_total_count") val currentWeeklyTotalCount: Long? = null,
    @SerialName("current_weekly_usage_count") val currentWeeklyUsageCount: Long? = null,
    @SerialName("current_weekly_used_count") val currentWeeklyUsedCount: Long? = null,
    @SerialName("current_weekly_remaining_count") val currentWeeklyRemainingCount: Long? = null,
    @SerialName("current_weekly_remains_count") val currentWeeklyRemainsCount: Long? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    @SerialName("current_weekly_remaining_percent") val currentWeeklyRemainingPercent: Double? = null,
    @SerialName("current_weekly_status") val currentWeeklyStatus: Int? = null,
    @SerialName("weekly_start_time") val weeklyStartTime: Long? = null,
    @SerialName("weekly_end_time") val weeklyEndTime: Long? = null,
    @SerialName("weekly_remains_time") val weeklyRemainsTime: Long? = null,
    @SerialName("current_subscribe_title") val currentSubscribeTitle: String? = null,
    @SerialName("plan_name") val planName: String? = null,
    val plan: String? = null,
)
