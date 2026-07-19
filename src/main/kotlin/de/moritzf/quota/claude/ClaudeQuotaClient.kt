package de.moritzf.quota.claude

import de.moritzf.quota.shared.JsonSupport
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

open class ClaudeQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val usageUri: URI = DEFAULT_USAGE_URI,
) {
    open fun fetchQuota(accessToken: String?): ClaudeQuota {
        val token = accessToken?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ClaudeQuotaException("Claude login required. Log in from Claude settings.")

        val body = getUsageJson(token)
        val quota = try {
            parseQuota(body)
        } catch (exception: ClaudeQuotaException) {
            throw ClaudeQuotaException(exception.message ?: "Claude usage response changed.", 200, body, exception)
        } catch (exception: Exception) {
            throw ClaudeQuotaException("Claude usage response changed.", 200, body, exception)
        }
        quota.rawJson = body
        return quota
    }

    private fun getUsageJson(accessToken: String): String {
        val request = HttpRequest.newBuilder()
            .uri(usageUri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("anthropic-beta", OAUTH_BETA)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401) {
            throw ClaudeQuotaException("Claude auth expired. Log in to Claude again from settings.", status, body)
        }
        if (status == 403) {
            if (body.contains("user:profile", ignoreCase = true)) {
                throw ClaudeQuotaException(
                    "Claude token is missing the user:profile scope required for usage. Log in again from Claude settings.",
                    status,
                    body,
                )
            }
            throw ClaudeQuotaException("Claude auth expired. Log in to Claude again from settings.", status, body)
        }
        if (status == 429) {
            throw ClaudeQuotaException("Claude usage API rate limited. Try again later.", status, body)
        }
        if (status !in 200..299) {
            throw ClaudeQuotaException("Claude usage request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw ClaudeQuotaException("Claude usage request timed out. Try again later.", 0, null, exception)
        } catch (exception: IOException) {
            throw ClaudeQuotaException("Claude usage request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ClaudeQuotaException("Claude usage request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        @JvmField
        val DEFAULT_USAGE_URI: URI = URI.create("https://api.anthropic.com/api/oauth/usage")

        private const val OAUTH_BETA = "oauth-2025-04-20"
        private const val USER_AGENT = "claude-cli/2.1.87 (external, cli)"
        private val FIVE_HOUR_MS = Duration.ofHours(5).toMillis()
        private val SEVEN_DAY_MS = Duration.ofDays(7).toMillis()

        fun parseQuota(
            usageJson: String,
            fetchedAt: Instant = Clock.System.now(),
        ): ClaudeQuota {
            val payload = runCatching { JsonSupport.json.decodeFromString<ClaudeUsageResponseDto>(usageJson) }
                .getOrElse { throw ClaudeQuotaException("Claude usage response changed.", 200, usageJson, it) }

            val fiveHour = payload.fiveHour.toWindow("5-hour", FIVE_HOUR_MS)
            val sevenDay = payload.sevenDay.toWindow("7-day", SEVEN_DAY_MS)
            val sevenDaySonnet = payload.sevenDaySonnet.toWindow("7-day Sonnet", SEVEN_DAY_MS)
            val sevenDayOpus = payload.sevenDayOpus.toWindow("7-day Opus", SEVEN_DAY_MS)
            val sevenDayOauthApps = payload.sevenDayOauthApps.toWindow("7-day OAuth apps", SEVEN_DAY_MS)
            val routines = firstRoutinesWindow(payload)
            val scopedLimits = scopedLimitWindows(payload)
            val extraUsage = payload.extraUsage?.toExtraUsage()

            val hasWindows = listOfNotNull(
                fiveHour,
                sevenDay,
                sevenDaySonnet,
                sevenDayOpus,
                sevenDayOauthApps,
                routines,
            ).isNotEmpty() || scopedLimits.isNotEmpty()
            if (!hasWindows && extraUsage?.isEnabled != true) {
                throw ClaudeQuotaException("Claude usage response changed.", 200, usageJson)
            }

            return ClaudeQuota(
                plan = "",
                fiveHourUsage = fiveHour,
                sevenDayUsage = sevenDay,
                sevenDaySonnetUsage = sevenDaySonnet,
                sevenDayOpusUsage = sevenDayOpus,
                sevenDayOauthAppsUsage = sevenDayOauthApps,
                routinesUsage = routines,
                scopedLimits = scopedLimits,
                extraUsage = extraUsage,
                fetchedAt = fetchedAt,
            )
        }

        /**
         * Model/surface-scoped entries from the `limits` array (for example a weekly cap for one
         * model). Unscoped entries duplicate the top-level windows and are skipped. Parsing is
         * lenient: entries without a usable percent or scope label are ignored.
         */
        private fun scopedLimitWindows(payload: ClaudeUsageResponseDto): List<ClaudeUsageWindow> {
            return payload.limits.orEmpty().mapNotNull { limit ->
                val percent = limit.percent ?: return@mapNotNull null
                val scopeLabel = limit.scope?.let { scope ->
                    scope.model?.displayName?.takeIf { it.isNotBlank() }
                        ?: scope.model?.id?.takeIf { it.isNotBlank() }
                        ?: scope.surface?.takeIf { it.isNotBlank() }
                } ?: return@mapNotNull null
                val group = limit.group?.takeIf { it.isNotBlank() } ?: limit.kind
                val groupLabel = when (group?.lowercase()) {
                    "session" -> "Session"
                    "weekly" -> "Weekly"
                    else -> group?.replaceFirstChar { it.uppercase() } ?: "Limit"
                }
                val periodDurationMs = when (group?.lowercase()) {
                    "session" -> FIVE_HOUR_MS
                    "weekly" -> SEVEN_DAY_MS
                    else -> null
                }
                ClaudeUsageWindow(
                    label = "$groupLabel ($scopeLabel)",
                    usagePercent = percent.coerceIn(0.0, 100.0),
                    resetsAt = limit.resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    periodDurationMs = periodDurationMs,
                )
            }
        }

        private fun firstRoutinesWindow(payload: ClaudeUsageResponseDto): ClaudeUsageWindow? {
            val candidates = listOf(
                payload.sevenDayRoutines,
                payload.sevenDayClaudeRoutines,
                payload.claudeRoutines,
                payload.routines,
                payload.routine,
                payload.sevenDayCowork,
                payload.cowork,
            )
            for (candidate in candidates) {
                candidate.toWindow("Daily Routines", SEVEN_DAY_MS)?.let { return it }
            }
            return null
        }

        private fun ClaudeUsageWindowDto?.toWindow(label: String, periodDurationMs: Long): ClaudeUsageWindow? {
            val utilization = this?.utilization ?: return null
            val resetsAt = this.resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            return ClaudeUsageWindow(
                label = label,
                usagePercent = utilization.coerceIn(0.0, 100.0),
                resetsAt = resetsAt,
                periodDurationMs = periodDurationMs,
            )
        }

        private fun ClaudeExtraUsageDto.toExtraUsage(): ClaudeExtraUsage {
            val limit = monthlyLimit
            val used = usedCredits
            val percent = utilization
                ?: if (limit != null && limit > 0 && used != null) {
                    (used.toDouble() / limit.toDouble() * 100.0).coerceIn(0.0, 100.0)
                } else {
                    null
                }
            return ClaudeExtraUsage(
                isEnabled = isEnabled == true,
                monthlyLimitCredits = limit,
                usedCredits = used,
                usagePercent = percent,
                currency = currency,
            )
        }
    }
}

@Serializable
private data class ClaudeUsageResponseDto(
    @SerialName("five_hour") val fiveHour: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day") val sevenDay: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day_sonnet") val sevenDaySonnet: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day_opus") val sevenDayOpus: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day_oauth_apps") val sevenDayOauthApps: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day_routines") val sevenDayRoutines: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day_claude_routines") val sevenDayClaudeRoutines: ClaudeUsageWindowDto? = null,
    @SerialName("claude_routines") val claudeRoutines: ClaudeUsageWindowDto? = null,
    @SerialName("routines") val routines: ClaudeUsageWindowDto? = null,
    @SerialName("routine") val routine: ClaudeUsageWindowDto? = null,
    @SerialName("seven_day_cowork") val sevenDayCowork: ClaudeUsageWindowDto? = null,
    @SerialName("cowork") val cowork: ClaudeUsageWindowDto? = null,
    @SerialName("extra_usage") val extraUsage: ClaudeExtraUsageDto? = null,
    val limits: List<ClaudeLimitDto>? = null,
)

@Serializable
private data class ClaudeUsageWindowDto(
    val utilization: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)

@Serializable
private data class ClaudeLimitDto(
    val kind: String? = null,
    val group: String? = null,
    val percent: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    val scope: ClaudeLimitScopeDto? = null,
)

@Serializable
private data class ClaudeLimitScopeDto(
    val model: ClaudeLimitModelDto? = null,
    val surface: String? = null,
)

@Serializable
private data class ClaudeLimitModelDto(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
private data class ClaudeExtraUsageDto(
    @SerialName("is_enabled") val isEnabled: Boolean? = null,
    @SerialName("monthly_limit") val monthlyLimit: Long? = null,
    @SerialName("used_credits") val usedCredits: Long? = null,
    val utilization: Double? = null,
    val currency: String? = null,
)
