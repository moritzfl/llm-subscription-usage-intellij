package de.moritzf.quota.github

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
import java.time.Duration

open class GitHubQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val usageEndpointOverride: URI? = null,
) {
    open fun fetchQuota(
        credentials: GitHubCredentials,
        enterpriseHost: String? = null,
    ): GitHubQuota {
        val accessToken = credentials.accessToken.ifBlank {
            throw GitHubQuotaException("GitHub login required. Log in from settings.")
        }

        val usageEndpoint = usageEndpointOverride ?: usageEndpoint(enterpriseHost)
        val request = HttpRequest.newBuilder()
            .uri(usageEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "token $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", GitHubOAuthClient.USER_AGENT)
            .header("Copilot-Integration-Id", "JetBrainsIDE")
            .GET()
            .build()
        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw GitHubQuotaException("Session expired. Log in to GitHub again from settings.", status, body)
        }
        if (status == 404) {
            throw GitHubQuotaException("No GitHub Copilot subscription found for this account.", status, body)
        }
        if (status !in 200..299) {
            throw GitHubQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        val quota = parseQuota(body)
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return quota
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw GitHubQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GitHubQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        private const val DEFAULT_HOST = "github.com"
        private const val DEFAULT_API_HOST = "api.github.com"

        /** Copilot quota windows reset monthly; the API only reports the reset date. */
        private const val MONTH_DURATION_MS = 30L * 24 * 60 * 60 * 1000

        fun usageEndpoint(enterpriseHost: String?): URI {
            return URI.create("https://${apiHost(enterpriseHost)}/copilot_internal/user")
        }

        fun apiHost(enterpriseHost: String?): String {
            val host = normalizedEnterpriseHost(enterpriseHost)
            if (host == DEFAULT_HOST) return DEFAULT_API_HOST
            if (host.startsWith("api.")) return host
            return "api.$host"
        }

        fun normalizedEnterpriseHost(raw: String?): String {
            var host = raw?.trim().orEmpty()
            if (host.isBlank()) return DEFAULT_HOST
            val componentsValue = if (host.contains("://")) host else "https://$host"
            val parsed = runCatching { URI.create(componentsValue) }.getOrNull()
            val parsedHost = parsed?.host
            if (!parsedHost.isNullOrBlank()) {
                host = parsedHost
                if (parsed.port >= 0) {
                    host += ":${parsed.port}"
                }
            } else {
                host = host.removePrefix("https://").removePrefix("http://")
                    .substringBefore('/')
            }
            return host.trim('.').lowercase().ifBlank { DEFAULT_HOST }
        }

        fun parseQuota(body: String): GitHubQuota {
            val dto = try {
                JsonSupport.json.decodeFromString<GitHubUserResponseDto>(body)
            } catch (exception: Exception) {
                throw GitHubQuotaException("Could not parse usage data.", 200, body, exception)
            }

            val paidResetsAt = parseResetDate(dto.quotaResetDate)
            val freeResetsAt = parseResetDate(dto.limitedUserResetDate) ?: paidResetsAt

            // Paid plans report percentage snapshots; the free tier reports absolute counters.
            val premium = dto.quotaSnapshots?.premiumInteractions?.toWindow("Premium requests", paidResetsAt)
            val chat = dto.quotaSnapshots?.chat?.toWindow("Chat", paidResetsAt)
                ?: dto.limitedUserQuotas?.chat?.toFreeWindow("Chat", dto.monthlyQuotas?.chat, freeResetsAt)
            val completions = dto.quotaSnapshots?.completions?.toWindow("Completions", paidResetsAt)
                ?: dto.limitedUserQuotas?.completions?.toFreeWindow("Completions", dto.monthlyQuotas?.completions, freeResetsAt)

            return GitHubQuota(
                plan = normalizePlan(dto.copilotPlan),
                subscriptionState = subscriptionState(dto),
                premiumInteractions = premium,
                chat = chat,
                completions = completions,
            )
        }


        private fun GitHubQuotaSnapshotDto.toWindow(label: String, resetsAt: Instant?): GitHubUsageWindow? {
            if (unlimited == true) {
                return GitHubUsageWindow(label = label, unlimited = true, resetsAt = resetsAt, periodDurationMs = MONTH_DURATION_MS)
            }
            val total = entitlement ?: quotaTotal ?: return null
            val remaining = (remaining ?: quotaRemaining ?: percentRemaining?.let { total * it / 100.0 })?.coerceAtLeast(0.0)
                ?: return null
            val used = (total - remaining).coerceAtLeast(0.0)
            return GitHubUsageWindow(
                label = label,
                used = used,
                limit = total,
                usagePercent = if (total > 0) used / total * 100.0 else 0.0,
                resetsAt = resetsAt,
                periodDurationMs = MONTH_DURATION_MS,
            )
        }

        private fun Long.toFreeWindow(label: String, monthlyMaximum: Long?, resetsAt: Instant?): GitHubUsageWindow? {
            val total = monthlyMaximum ?: return null
            val remaining = coerceAtLeast(0)
            val used = (total - remaining).coerceAtLeast(0)
            return GitHubUsageWindow(
                label = label,
                used = used.toDouble(),
                limit = total.toDouble(),
                usagePercent = if (total > 0) used.toDouble() / total.toDouble() * 100.0 else 0.0,
                resetsAt = resetsAt,
                periodDurationMs = MONTH_DURATION_MS,
            )
        }

        /** The API reports plain dates (e.g. "2026-07-01"); treat them as UTC midnight. */
        private fun parseResetDate(value: String?): Instant? {
            if (value.isNullOrBlank()) return null
            return runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { Instant.parse("${value}T00:00:00Z") }.getOrNull()
        }

        private fun subscriptionState(dto: GitHubUserResponseDto): GitHubSubscriptionState {
            if (dto.accessTypeSku.equals("subscription_ended", ignoreCase = true)) {
                return GitHubSubscriptionState.SUBSCRIPTION_ENDED
            }

            val hasQuotaPayload = dto.quotaSnapshots != null || dto.limitedUserQuotas != null || dto.monthlyQuotas != null
            if (dto.chatEnabled == false && dto.cliEnabled == false && !hasQuotaPayload) {
                return GitHubSubscriptionState.NO_ACTIVE_SUBSCRIPTION
            }

            return GitHubSubscriptionState.ACTIVE
        }

        private fun normalizePlan(plan: String?): String {
            return when (plan?.lowercase()) {
                "free" -> "Copilot Free"
                "individual" -> "Copilot Individual"
                "individual_pro", "pro" -> "Copilot Pro"
                "pro_plus" -> "Copilot Pro+"
                "business" -> "Copilot Business"
                "enterprise" -> "Copilot Enterprise"
                null, "" -> "GitHub Copilot"
                else -> "Copilot " + plan.replace('_', ' ').replaceFirstChar { it.uppercase() }
            }
        }
    }
}

@Serializable
private data class GitHubUserResponseDto(
    @SerialName("copilot_plan") val copilotPlan: String? = null,
    @SerialName("access_type_sku") val accessTypeSku: String? = null,
    @SerialName("chat_enabled") val chatEnabled: Boolean? = null,
    @SerialName("cli_enabled") val cliEnabled: Boolean? = null,
    @SerialName("quota_reset_date") val quotaResetDate: String? = null,
    @SerialName("quota_snapshots") val quotaSnapshots: GitHubQuotaSnapshotsDto? = null,
    @SerialName("limited_user_reset_date") val limitedUserResetDate: String? = null,
    @SerialName("limited_user_quotas") val limitedUserQuotas: GitHubLimitedQuotasDto? = null,
    @SerialName("monthly_quotas") val monthlyQuotas: GitHubLimitedQuotasDto? = null,
)

@Serializable
private data class GitHubQuotaSnapshotsDto(
    @SerialName("premium_interactions") val premiumInteractions: GitHubQuotaSnapshotDto? = null,
    val chat: GitHubQuotaSnapshotDto? = null,
    val completions: GitHubQuotaSnapshotDto? = null,
)

@Serializable
private data class GitHubQuotaSnapshotDto(
    val entitlement: Double? = null,
    @SerialName("quota_total") val quotaTotal: Double? = null,
    val remaining: Double? = null,
    @SerialName("quota_remaining") val quotaRemaining: Double? = null,
    @SerialName("percent_remaining") val percentRemaining: Double? = null,
    val unlimited: Boolean? = null,
)

@Serializable
private data class GitHubLimitedQuotasDto(
    val chat: Long? = null,
    val completions: Long? = null,
)
