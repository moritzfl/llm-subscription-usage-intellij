package de.moritzf.quota.cursor

import de.moritzf.quota.shared.JsonSupport
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * HTTP client for fetching Cursor subscription usage from the dashboard API.
 */
open class CursorQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
    private val webBaseUri: URI = DEFAULT_WEB_BASE_URI,
) {
    @Throws(IOException::class, InterruptedException::class)
    open fun fetchQuota(accessToken: String, auth: CursorAuth? = null): CursorQuota {
        require(accessToken.isNotBlank()) { "accessToken must not be null or blank" }

        val sessionCookie = auth?.sessionCookie?.trim().orEmpty()
        if (sessionCookie.isNotBlank()) {
            runCatching { fetchWebQuota(sessionCookie, auth) }.getOrNull()?.let { return it }
        }

        val periodUsageJson = postDashboard(accessToken, "GetCurrentPeriodUsage", auth)
        val planInfoJson = runCatching { postDashboard(accessToken, "GetPlanInfo", auth) }.getOrNull()
        val profileJson = runCatching { getRest(accessToken, "/auth/full_stripe_profile", auth) }.getOrNull()

        val rawJson = buildRawJson(periodUsageJson, planInfoJson, profileJson)
        val quota = try {
            parseQuota(periodUsageJson, planInfoJson, profileJson, auth)
        } catch (exception: CursorQuotaException) {
            throw CursorQuotaException(exception.message ?: "Usage response invalid.", exception.statusCode, rawJson, exception)
        } catch (exception: IllegalArgumentException) {
            throw CursorQuotaException("Usage response invalid.", 200, rawJson, exception)
        }

        quota.fetchedAt = Clock.System.now()
        quota.rawJson = rawJson
        return quota
    }

    private fun fetchWebQuota(sessionCookie: String, auth: CursorAuth?): CursorQuota {
        val usageSummaryJson = getCookieRest("/api/usage-summary", sessionCookie)
        val userInfoJson = runCatching { getCookieRest("/api/auth/me", sessionCookie) }.getOrNull()
        val userId = userInfoJson?.let(::parseUserIdFromUserInfo)
            ?: CursorSessionTokenParser.extractUserId(sessionCookie)
        val requestUsageJson = userId?.let {
            runCatching { getCookieRest("/api/usage?user=${encodeQueryValue(it)}", sessionCookie) }.getOrNull()
        }

        val rawJson = buildWebRawJson(usageSummaryJson, userInfoJson, requestUsageJson)
        val quota = try {
            parseUsageSummary(usageSummaryJson, userInfoJson, requestUsageJson, auth)
        } catch (exception: CursorQuotaException) {
            throw CursorQuotaException(exception.message ?: "Usage response invalid.", exception.statusCode, rawJson, exception)
        } catch (exception: IllegalArgumentException) {
            throw CursorQuotaException("Usage response invalid.", 200, rawJson, exception)
        }

        quota.fetchedAt = Clock.System.now()
        quota.rawJson = rawJson
        return quota
    }

    private fun postDashboard(accessToken: String, method: String, auth: CursorAuth? = null): String {
        val builder = HttpRequest.newBuilder()
            .uri(baseUri.resolve("/aiserver.v1.DashboardService/$method"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
        applySessionCookie(builder, auth)
        return send(builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build())
    }

    private fun getRest(accessToken: String, path: String, auth: CursorAuth? = null): String {
        val builder = HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
        applySessionCookie(builder, auth)
        return send(builder.GET().build())
    }

    private fun getCookieRest(path: String, sessionCookie: String): String {
        val builder = HttpRequest.newBuilder()
            .uri(webBaseUri.resolve(path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .header("Cookie", CursorSessionTokenParser.buildCookieHeader(sessionCookie))
        return send(builder.GET().build())
    }

    private fun applySessionCookie(builder: HttpRequest.Builder, auth: CursorAuth?) {
        val sessionCookie = auth?.sessionCookie?.trim().orEmpty()
        if (sessionCookie.isNotBlank()) {
            builder.header("Cookie", CursorSessionTokenParser.buildCookieHeader(sessionCookie))
        }
    }

    private fun send(request: HttpRequest): String {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw CursorQuotaException("Usage request failed. Check your connection.", 0, null, exception)
        }

        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw CursorQuotaException(
                "Cursor session is invalid or expired. Update WorkosCursorSessionToken in settings.",
                status,
                body,
            )
        }
        if (status !in 200..299) {
            throw CursorQuotaException("Usage request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    companion object {
        @JvmField
        val DEFAULT_BASE_URI: URI = URI.create("https://api2.cursor.sh")

        @JvmField
        val DEFAULT_WEB_BASE_URI: URI = URI.create("https://cursor.com")

        fun parseQuota(
            periodUsageJson: String,
            planInfoJson: String?,
            profileJson: String?,
            auth: CursorAuth? = null,
        ): CursorQuota {
            val periodUsage = JsonSupport.json.decodeFromString<CurrentPeriodUsageResponse>(periodUsageJson)
            val planInfo = planInfoJson?.let { JsonSupport.json.decodeFromString<PlanInfoResponse>(it) }
            val profile = profileJson?.let { JsonSupport.json.decodeFromString<StripeProfileResponse>(it) }

            val planUsage = CursorPlanUsage(
                totalPercentUsed = periodUsage.planUsage.totalPercentUsed,
                autoPercentUsed = periodUsage.planUsage.autoPercentUsed,
                apiPercentUsed = periodUsage.planUsage.apiPercentUsed,
                totalSpendUsd = periodUsage.planUsage.totalSpend / 100.0,
                limitUsd = periodUsage.planUsage.limit / 100.0,
                billingCycleStart = parseTimestamp(periodUsage.billingCycleStart),
                billingCycleEnd = parseTimestamp(periodUsage.billingCycleEnd),
            )

            val spendLimit = periodUsage.spendLimitUsage.pooledLimit.takeIf { it > 0.0 }?.let {
                CursorSpendLimit(
                    pooledLimitUsd = periodUsage.spendLimitUsage.pooledLimit / 100.0,
                    pooledUsedUsd = periodUsage.spendLimitUsage.pooledUsed / 100.0,
                    pooledRemainingUsd = periodUsage.spendLimitUsage.pooledRemaining / 100.0,
                    limitType = periodUsage.spendLimitUsage.limitType,
                )
            }

            if (planUsage.totalPercentUsed == 0.0 &&
                planUsage.autoPercentUsed == 0.0 &&
                planUsage.apiPercentUsed == 0.0 &&
                spendLimit == null
            ) {
                throw CursorQuotaException("Could not parse Cursor quota response.", 200, periodUsageJson)
            }

            return CursorQuota(
                planName = planInfo?.planInfo?.planName.orEmpty(),
                email = auth?.email.orEmpty(),
                membershipType = profile?.membershipType ?: auth?.membershipType.orEmpty(),
                planUsage = planUsage,
                spendLimit = spendLimit,
                displayMessage = periodUsage.displayMessage,
                autoModelDisplayMessage = periodUsage.autoModelSelectedDisplayMessage,
                apiModelDisplayMessage = periodUsage.namedModelSelectedDisplayMessage,
            )
        }

        fun parseUsageSummary(
            usageSummaryJson: String,
            userInfoJson: String?,
            requestUsageJson: String?,
            auth: CursorAuth? = null,
        ): CursorQuota {
            val summary = JsonSupport.json.decodeFromString<UsageSummaryResponse>(usageSummaryJson)
            val userInfo = userInfoJson?.let { JsonSupport.json.decodeFromString<UserInfoResponse>(it) }
            val requestUsageResponse = requestUsageJson?.let { JsonSupport.json.decodeFromString<RequestUsageResponse>(it) }

            val individualUsage = summary.individualUsage
            val teamUsage = summary.teamUsage
            val plan = individualUsage?.plan
            val autoPercent = normalizePercent(plan?.autoPercentUsed)
            val apiPercent = normalizePercent(plan?.apiPercentUsed)
            val totalPercent = plan?.totalPercentUsed?.let(::normalizeRequiredPercent)

            val planUsedRaw = plan?.used ?: 0.0
            val planLimitRaw = plan?.limit ?: 0.0
            val overallUsedRaw = individualUsage?.overall?.used
            val overallLimitRaw = individualUsage?.overall?.limit
            val pooledUsedRaw = teamUsage?.pooled?.used
            val pooledLimitRaw = teamUsage?.pooled?.limit

            val planPercentUsed = when {
                totalPercent != null -> totalPercent
                autoPercent != null && apiPercent != null -> normalizeRequiredPercent((autoPercent + apiPercent) / 2.0)
                apiPercent != null -> apiPercent
                autoPercent != null -> autoPercent
                planLimitRaw > 0.0 -> normalizeRequiredPercent((planUsedRaw / planLimitRaw) * 100.0)
                overallUsedRaw != null && overallLimitRaw != null && overallLimitRaw > 0.0 -> {
                    normalizeRequiredPercent((overallUsedRaw / overallLimitRaw) * 100.0)
                }
                pooledUsedRaw != null && pooledLimitRaw != null && pooledLimitRaw > 0.0 -> {
                    normalizeRequiredPercent((pooledUsedRaw / pooledLimitRaw) * 100.0)
                }
                else -> 0.0
            }

            val (planUsedUsd, planLimitUsd) = when {
                planLimitRaw > 0.0 || planUsedRaw > 0.0 -> centsToUsd(planUsedRaw) to centsToUsd(planLimitRaw)
                overallUsedRaw != null || overallLimitRaw != null -> {
                    centsToUsd(overallUsedRaw ?: 0.0) to centsToUsd(overallLimitRaw ?: 0.0)
                }
                pooledUsedRaw != null || pooledLimitRaw != null -> {
                    centsToUsd(pooledUsedRaw ?: 0.0) to centsToUsd(pooledLimitRaw ?: 0.0)
                }
                else -> 0.0 to 0.0
            }

            val hasPlanUsage = plan != null || individualUsage?.overall != null || teamUsage?.pooled != null ||
                totalPercent != null || autoPercent != null || apiPercent != null
            val planUsage = if (hasPlanUsage) {
                CursorPlanUsage(
                    totalPercentUsed = planPercentUsed,
                    autoPercentUsed = autoPercent ?: 0.0,
                    apiPercentUsed = apiPercent ?: 0.0,
                    totalSpendUsd = planUsedUsd,
                    limitUsd = planLimitUsd,
                    billingCycleStart = parseTimestamp(summary.billingCycleStart),
                    billingCycleEnd = parseTimestamp(summary.billingCycleEnd),
                )
            } else {
                null
            }

            val requestUsage = requestUsageResponse?.gpt4?.let { model ->
                val used = model.numRequestsTotal ?: model.numRequests
                val limit = model.maxRequestUsage
                if (used != null && limit != null && limit > 0) CursorRequestUsage(used = used, limit = limit) else null
            }
            val onDemandUsage = individualUsage?.onDemand.toUsage(scope = "personal")
            val teamOnDemandUsage = teamUsage?.onDemand.toUsage(scope = "team")

            if (planUsage == null && requestUsage == null && onDemandUsage == null && teamOnDemandUsage == null) {
                throw CursorQuotaException("Could not parse Cursor quota response.", 200, usageSummaryJson)
            }

            return CursorQuota(
                email = userInfo?.email ?: auth?.email.orEmpty(),
                membershipType = summary.membershipType ?: auth?.membershipType.orEmpty(),
                planUsage = planUsage,
                onDemandUsage = onDemandUsage,
                teamOnDemandUsage = teamOnDemandUsage,
                requestUsage = requestUsage,
                displayMessage = summary.autoModelSelectedDisplayMessage.orEmpty(),
                autoModelDisplayMessage = summary.autoModelSelectedDisplayMessage.orEmpty(),
                apiModelDisplayMessage = summary.namedModelSelectedDisplayMessage.orEmpty(),
            )
        }

        internal fun buildRawJson(periodUsageJson: String, planInfoJson: String?, profileJson: String?): String {
            return buildJsonObject {
                put("periodUsage", periodUsageJson)
                planInfoJson?.let { put("planInfo", it) }
                profileJson?.let { put("profile", it) }
            }.toString()
        }

        internal fun buildWebRawJson(usageSummaryJson: String, userInfoJson: String?, requestUsageJson: String?): String {
            return buildJsonObject {
                put("usageSummary", usageSummaryJson)
                userInfoJson?.let { put("userInfo", it) }
                requestUsageJson?.let { put("requestUsage", it) }
            }.toString()
        }

        internal fun normalizeRawJson(json: String): String {
            return runCatching {
                val root = JsonSupport.json.parseToJsonElement(json).jsonObject
                val needsNormalization = listOf(
                    "periodUsage",
                    "planInfo",
                    "profile",
                    "usageSummary",
                    "userInfo",
                    "requestUsage",
                ).any { key ->
                    val value = root[key] as? JsonPrimitive
                    value?.isString == true
                }
                if (!needsNormalization) {
                    return json
                }

                val normalized = buildJsonObject {
                    root.forEach { (key, value) ->
                        put(key, unwrapEmbeddedJson(value))
                    }
                }
                JsonSupport.json.encodeToString(JsonObject.serializer(), normalized)
            }.getOrElse { json }
        }

        private fun unwrapEmbeddedJson(value: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement {
            val primitive = value as? JsonPrimitive
            if (primitive?.isString != true) {
                return value
            }
            return runCatching { JsonSupport.json.parseToJsonElement(primitive.content) }.getOrElse { value }
        }

        internal fun parseTimestamp(value: String?): Instant? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            trimmed.toLongOrNull()?.let { epoch ->
                val millis = when {
                    epoch > 1_000_000_000_000_000L -> epoch / 1_000L
                    epoch > 1_000_000_000_000L -> epoch
                    else -> epoch * 1_000L
                }
                return Instant.fromEpochMilliseconds(millis)
            }
            return runCatching { Instant.parse(trimmed) }.getOrNull()
        }

        private fun parseUserIdFromUserInfo(userInfoJson: String): String? {
            return runCatching { JsonSupport.json.decodeFromString<UserInfoResponse>(userInfoJson).sub }.getOrNull()
        }

        private fun encodeQueryValue(value: String): String {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }

        private fun normalizePercent(value: Double?): Double? {
            return value?.let(::normalizeRequiredPercent)
        }

        private fun normalizeRequiredPercent(value: Double): Double {
            return value.coerceIn(0.0, 100.0)
        }

        private fun centsToUsd(value: Double): Double = value / 100.0

        private fun UsageBudgetResponse?.toUsage(scope: String): CursorOnDemandUsage? {
            val budget = this ?: return null
            if (budget.enabled != true && budget.used == null && budget.limit == null && budget.remaining == null) {
                return null
            }
            return CursorOnDemandUsage(
                usedUsd = centsToUsd(budget.used ?: 0.0),
                limitUsd = budget.limit?.let(::centsToUsd),
                remainingUsd = budget.remaining?.let(::centsToUsd),
                scope = scope,
                enabled = budget.enabled != false,
            )
        }
    }
}

@Serializable
private data class UsageSummaryResponse(
    val billingCycleStart: String? = null,
    val billingCycleEnd: String? = null,
    val membershipType: String? = null,
    val limitType: String? = null,
    val isUnlimited: Boolean? = null,
    val autoModelSelectedDisplayMessage: String? = null,
    val namedModelSelectedDisplayMessage: String? = null,
    val individualUsage: IndividualUsageResponse? = null,
    val teamUsage: TeamUsageResponse? = null,
)

@Serializable
private data class IndividualUsageResponse(
    val plan: UsageSummaryPlanResponse? = null,
    val onDemand: UsageBudgetResponse? = null,
    val overall: UsageBudgetResponse? = null,
)

@Serializable
private data class TeamUsageResponse(
    val onDemand: UsageBudgetResponse? = null,
    val pooled: UsageBudgetResponse? = null,
)

@Serializable
private data class UsageSummaryPlanResponse(
    val enabled: Boolean? = null,
    val used: Double? = null,
    val limit: Double? = null,
    val remaining: Double? = null,
    val breakdown: UsageSummaryPlanBreakdownResponse? = null,
    val autoPercentUsed: Double? = null,
    val apiPercentUsed: Double? = null,
    val totalPercentUsed: Double? = null,
)

@Serializable
private data class UsageSummaryPlanBreakdownResponse(
    val included: Double? = null,
    val bonus: Double? = null,
    val total: Double? = null,
)

@Serializable
private data class UsageBudgetResponse(
    val enabled: Boolean? = null,
    val used: Double? = null,
    val limit: Double? = null,
    val remaining: Double? = null,
)

@Serializable
private data class RequestUsageResponse(
    @SerialName("gpt-4") val gpt4: CursorModelUsageResponse? = null,
    val startOfMonth: String? = null,
)

@Serializable
private data class CursorModelUsageResponse(
    val numRequests: Int? = null,
    val numRequestsTotal: Int? = null,
    val numTokens: Int? = null,
    val maxRequestUsage: Int? = null,
    val maxTokenUsage: Int? = null,
)

@Serializable
private data class UserInfoResponse(
    val email: String? = null,
    val name: String? = null,
    val sub: String? = null,
    @SerialName("email_verified") val emailVerified: Boolean? = null,
)

@Serializable
private data class CurrentPeriodUsageResponse(
    val billingCycleStart: String = "",
    val billingCycleEnd: String = "",
    val planUsage: PlanUsageResponse = PlanUsageResponse(),
    val spendLimitUsage: SpendLimitUsageResponse = SpendLimitUsageResponse(),
    val displayThreshold: Double = 0.0,
    val displayMessage: String = "",
    val autoModelSelectedDisplayMessage: String = "",
    val namedModelSelectedDisplayMessage: String = "",
)

@Serializable
private data class PlanUsageResponse(
    val totalSpend: Double = 0.0,
    val includedSpend: Double = 0.0,
    val bonusSpend: Double = 0.0,
    val limit: Double = 0.0,
    val autoPercentUsed: Double = 0.0,
    val apiPercentUsed: Double = 0.0,
    val totalPercentUsed: Double = 0.0,
)

@Serializable
private data class SpendLimitUsageResponse(
    val totalSpend: Double = 0.0,
    val pooledLimit: Double = 0.0,
    val pooledUsed: Double = 0.0,
    val pooledRemaining: Double = 0.0,
    val individualUsed: Double = 0.0,
    val limitType: String = "",
)

@Serializable
private data class PlanInfoResponse(
    val planInfo: PlanInfoDetails = PlanInfoDetails(),
)

@Serializable
private data class PlanInfoDetails(
    val planName: String = "",
    val includedAmountCents: Double = 0.0,
    val price: String = "",
    val billingCycleEnd: String = "",
)

@Serializable
private data class StripeProfileResponse(
    val membershipType: String = "",
    @SerialName("isTeamMember") val isTeamMember: Boolean = false,
)
