package de.moritzf.quota.cursor

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** HTTP client for fetching Cursor subscription usage from the dashboard API. */
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
            runCatching { fetchWebQuota(sessionCookie, auth) }
                .getOrNull()
                ?.let {
                    return it
                }
        }

        val periodUsageJson = postDashboard(accessToken, "GetCurrentPeriodUsage", auth)
        val planInfoJson = runCatching {
            postDashboard(accessToken, "GetPlanInfo", auth)
        }
            .getOrNull()
        val profileJson = runCatching {
            getStripeProfile(accessToken, auth)
        }
            .getOrNull()
        val sandJson = runCatching {
            postDashboard(accessToken, "GetSandUsageStatus", auth, OPTIONAL_TIMEOUT)
        }
            .getOrNull()

        val rawJson = buildRawJson(periodUsageJson, planInfoJson, profileJson, sandJson)
        val quota =
            try {
                parseQuota(periodUsageJson, planInfoJson, profileJson, auth, sandJson)
            } catch (exception: CursorQuotaException) {
                throw CursorQuotaException(
                    exception.message ?: "Usage response invalid.",
                    exception.statusCode,
                    rawJson,
                    exception,
                )
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
        // Combined plans report their spend through usage-status; the request lookup is legacy.
        val unifiedJson =
            if (isUnifiedUserPlan(usageSummaryJson)) {
                optionalCookieRequest(
                    cookieRequest("/api/dashboard/v2/usage-status?scope=user", sessionCookie).GET()
                )
            } else {
                null
            }
        val requestUsageJson =
            if (unifiedJson != null) null
            else {
                val userId =
                    userInfoJson?.let(::parseUserIdFromUserInfo)
                        ?: CursorSessionTokenParser.extractUserId(sessionCookie)
                userId?.let {
                    runCatching {
                        getCookieRest("/api/usage?user=${encodeQueryValue(it)}", sessionCookie)
                    }
                        .getOrNull()
                }
            }
        val sandJson =
            optionalCookieRequest(
                cookieRequest("/api/dashboard/get-sand-usage-status", sessionCookie)
                    .header("Content-Type", "application/json")
                    .header("Origin", webBaseUri.toString().trimEnd('/'))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
            )

        val rawJson =
            buildWebRawJson(usageSummaryJson, userInfoJson, requestUsageJson, sandJson, unifiedJson)
        val quota =
            try {
                parseUsageSummary(
                    usageSummaryJson,
                    userInfoJson,
                    requestUsageJson,
                    auth,
                    sandJson,
                    unifiedJson,
                )
            } catch (exception: CursorQuotaException) {
                throw CursorQuotaException(
                    exception.message ?: "Usage response invalid.",
                    exception.statusCode,
                    rawJson,
                    exception,
                )
            } catch (exception: IllegalArgumentException) {
                throw CursorQuotaException("Usage response invalid.", 200, rawJson, exception)
            }

        quota.fetchedAt = Clock.System.now()
        quota.rawJson = rawJson
        return quota
    }

    private fun postDashboard(
        accessToken: String,
        method: String,
        auth: CursorAuth? = null,
        timeout: Duration = Duration.ofSeconds(30),
    ): String {
        val builder =
            HttpRequest.newBuilder()
                .uri(baseUri.resolve("/aiserver.v1.DashboardService/$method"))
                .timeout(timeout)
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
        applySessionCookie(builder, auth)
        return send(builder.POST(HttpRequest.BodyPublishers.ofString("{}")).build())
    }

    private fun getStripeProfile(accessToken: String, auth: CursorAuth?): String {
        val builder =
            HttpRequest.newBuilder()
                .uri(baseUri.resolve("/auth/full_stripe_profile"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
        applySessionCookie(builder, auth)
        return send(builder.GET().build())
    }

    private fun cookieRequest(path: String, sessionCookie: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(webBaseUri.resolve(path))
            .timeout(OPTIONAL_TIMEOUT)
            .header("Accept", "application/json")
            .header("Cookie", CursorSessionTokenParser.buildCookieHeader(sessionCookie))

    /**
     * Optional dashboard data: anything but a successful answer, including the redirect for a
     * missing session or the 403 for a missing Origin, means no data rather than an expired login.
     */
    private fun optionalCookieRequest(builder: HttpRequest.Builder): String? = runCatching {
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
        .getOrElse { exception ->
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            null
        }
        ?.takeIf { it.statusCode() in 200..299 }
        ?.body()

    private fun getCookieRest(path: String, sessionCookie: String): String {
        val builder =
            HttpRequest.newBuilder()
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
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (exception: IOException) {
                throw CursorQuotaException(
                    "Usage request failed. Check your connection.",
                    0,
                    null,
                    exception,
                )
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
            throw CursorQuotaException(
                "Usage request failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return body
    }

    companion object {
        @JvmField val DEFAULT_BASE_URI: URI = URI.create("https://api2.cursor.sh")

        @JvmField val DEFAULT_WEB_BASE_URI: URI = URI.create("https://cursor.com")

        private val OPTIONAL_TIMEOUT: Duration = Duration.ofSeconds(5)

        fun parseQuota(
            periodUsageJson: String,
            planInfoJson: String?,
            profileJson: String?,
            auth: CursorAuth? = null,
            sandJson: String? = null,
            now: Instant = Clock.System.now(),
        ): CursorQuota {
            val periodUsage =
                JsonSupport.json.decodeFromString<CurrentPeriodUsageResponse>(periodUsageJson)
            // Supplementary documents only enrich the quota; if one is unparsable, keep the usage
            // data.
            val planInfo = planInfoJson?.let {
                runCatching { JsonSupport.json.decodeFromString<PlanInfoResponse>(it) }.getOrNull()
            }
            val profile = profileJson?.let {
                runCatching { JsonSupport.json.decodeFromString<StripeProfileResponse>(it) }
                    .getOrNull()
            }

            val sand = parseSand(sandJson)
            val grok = sand?.toGrokUsage(now)
            val weeklyReset =
                planInfo
                    ?.planInfo
                    ?.takeIf { it.includedUsagePeriod?.isWeekly() == true }
                    ?.let { parseTimestamp(it.includedUsageResetsAt) ?: grok?.resetsAt }
            val planUsage =
                CursorPlanUsage(
                        totalPercentUsed = normalizePercent(periodUsage.planUsage.totalPercentUsed),
                        autoPercentUsed = normalizePercent(periodUsage.planUsage.autoPercentUsed),
                        apiPercentUsed = normalizePercent(periodUsage.planUsage.apiPercentUsed),
                        totalSpendUsd = periodUsage.planUsage.totalSpend / 100.0,
                        limitUsd = periodUsage.planUsage.limit / 100.0,
                        billingCycleStart = parseTimestamp(periodUsage.billingCycleStart),
                        billingCycleEnd = parseTimestamp(periodUsage.billingCycleEnd),
                        weeklyResetsAt = weeklyReset,
                    )
                    .takeIf { it.usagePercent() != null || it.totalSpendUsd > 0.0 }

            val spendLimit =
                periodUsage.spendLimitUsage.pooledLimit
                    .takeIf { it > 0.0 }
                    ?.let {
                        CursorSpendLimit(
                            pooledLimitUsd = periodUsage.spendLimitUsage.pooledLimit / 100.0,
                            pooledUsedUsd = periodUsage.spendLimitUsage.pooledUsed / 100.0,
                            pooledRemainingUsd =
                                periodUsage.spendLimitUsage.pooledRemaining / 100.0,
                            limitType = periodUsage.spendLimitUsage.limitType,
                        )
                    }

            if (planUsage == null && spendLimit == null) {
                throw CursorQuotaException(
                    "Could not parse Cursor quota response.",
                    200,
                    periodUsageJson,
                )
            }
            val merged = planInfo?.isMerged == true || sand?.isUnified == true

            return CursorQuota(
                planName = planInfo?.planInfo?.planName.orEmpty(),
                grokUsage = grok?.takeUnless { merged },
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
            sandJson: String? = null,
            unifiedJson: String? = null,
            now: Instant = Clock.System.now(),
        ): CursorQuota {
            val summary = JsonSupport.json.decodeFromString<UsageSummaryResponse>(usageSummaryJson)
            val sand = parseSand(sandJson)
            val grok = sand?.toGrokUsage(now)
            val unified = summary.isUnifiedUserPlan() || sand?.isUnified == true
            // Supplementary documents only enrich the quota; if one is unparsable, keep the usage
            // data.
            val userInfo = userInfoJson?.let {
                runCatching { JsonSupport.json.decodeFromString<UserInfoResponse>(it) }.getOrNull()
            }
            val requestUsageResponse = requestUsageJson?.let {
                runCatching { JsonSupport.json.decodeFromString<RequestUsageResponse>(it) }
                    .getOrNull()
            }

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

            val (planUsedUsd, planLimitUsd) =
                when {
                    planLimitRaw > 0.0 || planUsedRaw > 0.0 ->
                        centsToUsd(planUsedRaw) to centsToUsd(planLimitRaw)
                    overallUsedRaw != null || overallLimitRaw != null -> {
                        centsToUsd(overallUsedRaw ?: 0.0) to centsToUsd(overallLimitRaw ?: 0.0)
                    }
                    pooledUsedRaw != null || pooledLimitRaw != null -> {
                        centsToUsd(pooledUsedRaw ?: 0.0) to centsToUsd(pooledLimitRaw ?: 0.0)
                    }
                    else -> 0.0 to 0.0
                }

            val hasPlanUsage =
                plan != null ||
                    individualUsage?.overall != null ||
                    teamUsage?.pooled != null ||
                    totalPercent != null ||
                    autoPercent != null ||
                    apiPercent != null
            val planUsage =
                if (hasPlanUsage) {
                    CursorPlanUsage(
                            totalPercentUsed = totalPercent,
                            autoPercentUsed = autoPercent,
                            apiPercentUsed = apiPercent,
                            totalSpendUsd = planUsedUsd,
                            limitUsd = planLimitUsd,
                            billingCycleStart = parseTimestamp(summary.billingCycleStart),
                            billingCycleEnd = parseTimestamp(summary.billingCycleEnd),
                            // On combined plans the included pool is the weekly Grok Bot pool.
                            weeklyResetsAt = grok?.resetsAt?.takeIf { unified },
                        )
                        .takeIf { it.usagePercent() != null || it.totalSpendUsd > 0.0 }
                } else {
                    null
                }

            val requestUsage =
                requestUsageResponse?.gpt4?.let { model ->
                    val used = model.numRequestsTotal ?: model.numRequests
                    val limit = model.maxRequestUsage
                    if (used != null && limit != null && limit > 0)
                        CursorRequestUsage(used = used, limit = limit)
                    else null
                }
            // The summary reports no on-demand spend on combined plans; usage-status does.
            val onDemandUsage =
                unifiedJson?.let { parseUnifiedOnDemand(it, now) }
                    ?: individualUsage?.onDemand.toUsage(scope = "personal")
            val teamOnDemandUsage = teamUsage?.onDemand.toUsage(scope = "team")

            val grokUsage = grok?.takeUnless { unified || requestUsage != null }
            if (
                planUsage == null &&
                    requestUsage == null &&
                    onDemandUsage == null &&
                    teamOnDemandUsage == null &&
                    grokUsage == null
            ) {
                throw CursorQuotaException(
                    "Could not parse Cursor quota response.",
                    200,
                    usageSummaryJson,
                )
            }

            return CursorQuota(
                planName =
                    if (summary.isUnifiedUserPlan()) {
                        sand?.cursorPlanName?.takeIf { it.isNotBlank() }
                            ?: summary.membershipType
                                .orEmpty()
                                .removePrefix("unified_")
                                .replaceFirstChar { it.uppercase() }
                    } else "",
                email = userInfo?.email ?: auth?.email.orEmpty(),
                membershipType = summary.membershipType ?: auth?.membershipType.orEmpty(),
                grokUsage = grokUsage,
                planUsage = planUsage,
                onDemandUsage = onDemandUsage,
                teamOnDemandUsage = teamOnDemandUsage,
                requestUsage = requestUsage,
                displayMessage = summary.autoModelSelectedDisplayMessage.orEmpty(),
                autoModelDisplayMessage = summary.autoModelSelectedDisplayMessage.orEmpty(),
                apiModelDisplayMessage = summary.namedModelSelectedDisplayMessage.orEmpty(),
            )
        }

        internal fun buildRawJson(
            periodUsageJson: String,
            planInfoJson: String?,
            profileJson: String?,
            sandJson: String? = null,
        ): String {
            return buildJsonObject {
                put("periodUsage", periodUsageJson)
                planInfoJson?.let { put("planInfo", it) }
                profileJson?.let { put("profile", it) }
                sandJson?.let { put("sandUsage", it) }
            }
                .toString()
        }

        internal fun buildWebRawJson(
            usageSummaryJson: String,
            userInfoJson: String?,
            requestUsageJson: String?,
            sandJson: String? = null,
            unifiedJson: String? = null,
        ): String {
            return buildJsonObject {
                put("usageSummary", usageSummaryJson)
                userInfoJson?.let { put("userInfo", it) }
                requestUsageJson?.let { put("requestUsage", it) }
                sandJson?.let { put("sandUsage", it) }
                unifiedJson?.let { put("unifiedUsage", it) }
            }
                .toString()
        }

        /** Combined plans ("The X Subscription") bill one user-level pool. */
        private fun isUnifiedUserPlan(usageSummaryJson: String): Boolean = runCatching {
            JsonSupport.json
                .decodeFromString<UsageSummaryResponse>(usageSummaryJson)
                .isUnifiedUserPlan()
        }
            .getOrDefault(false)

        private fun UsageSummaryResponse.isUnifiedUserPlan(): Boolean =
            membershipType.orEmpty().lowercase().startsWith("unified_") &&
                limitType.orEmpty().equals("user", ignoreCase = true)

        private fun parseSand(json: String?): SandUsageResponse? = json?.let {
            runCatching { JsonSupport.json.decodeFromString<SandUsageResponse>(it) }.getOrNull()
        }

        /**
         * The Grok Bot allowance counts only with an included limit, or during an unexpired trial,
         * which has no reset. The paid allowance resets weekly.
         */
        private fun SandUsageResponse.toGrokUsage(now: Instant): CursorGrokUsage? {
            val percent = usagePercent?.takeIf { it.isFinite() } ?: return null
            val hasLimit = includedLimitZero?.let { !it } ?: hasNonZeroIncludedLimit
            val trialEnds = parseTimestamp(sandTrialExpiresAt)?.takeIf { it > now }
            val trial = hasLimit != true && trialEnds != null
            if (hasLimit != true && !trial) return null
            return CursorGrokUsage(
                usagePercent = percent.coerceIn(0.0, 100.0),
                resetsAt = if (trial) null else parseTimestamp(nextResetTimestampUtc),
                trialEndsAt = if (trial) trialEnds else null,
            )
        }

        /** Monthly on-demand spend from usage-status, when it is available and current. */
        private fun parseUnifiedOnDemand(json: String, now: Instant): CursorOnDemandUsage? {
            val status =
                runCatching { JsonSupport.json.decodeFromString<UnifiedUsageResponse>(json) }
                    .getOrNull() ?: return null
            if (status.availability != "available") return null
            val monthly = status.monthlyUsage ?: return null
            val used = monthly.usedCents?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val limit = monthly.limitCents?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val start = parseTimestamp(monthly.periodStart) ?: return null
            val end = parseTimestamp(monthly.resetsAt) ?: return null
            if (now < start || now >= end) return null
            return CursorOnDemandUsage(
                usedUsd = centsToUsd(used),
                limitUsd = centsToUsd(limit),
                remainingUsd = centsToUsd((limit - used).coerceAtLeast(0.0)),
                scope = "personal",
            )
        }

        private fun String.isWeekly(): Boolean =
            contains("WEEKLY", ignoreCase = true) || this == "2"

        internal fun normalizeRawJson(json: String): String {
            return runCatching {
                val root = JsonSupport.json.parseToJsonElement(json).jsonObject
                val needsNormalization =
                    listOf(
                            "periodUsage",
                            "planInfo",
                            "profile",
                            "usageSummary",
                            "userInfo",
                            "requestUsage",
                            "sandUsage",
                            "unifiedUsage",
                        )
                        .any { key ->
                            val value = root[key] as? JsonPrimitive
                            value?.isString == true
                        }
                if (!needsNormalization) {
                    return json
                }

                val normalized = buildJsonObject {
                    root.forEach { (key, value) -> put(key, unwrapEmbeddedJson(value)) }
                }
                JsonSupport.json.encodeToString(JsonObject.serializer(), normalized)
            }
                .getOrElse { json }
        }

        private fun unwrapEmbeddedJson(
            value: kotlinx.serialization.json.JsonElement
        ): kotlinx.serialization.json.JsonElement {
            val primitive = value as? JsonPrimitive
            if (primitive?.isString != true) {
                return value
            }
            return runCatching { JsonSupport.json.parseToJsonElement(primitive.content) }
                .getOrElse { value }
        }

        internal fun parseTimestamp(value: String?): Instant? {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            trimmed.toLongOrNull()?.let { epoch ->
                val millis =
                    when {
                        epoch > 1_000_000_000_000_000L -> epoch / 1_000L
                        epoch > 1_000_000_000_000L -> epoch
                        else -> epoch * 1_000L
                    }
                return Instant.fromEpochMilliseconds(millis)
            }
            return runCatching { Instant.parse(trimmed) }.getOrNull()
        }

        private fun parseUserIdFromUserInfo(userInfoJson: String): String? {
            return runCatching {
                JsonSupport.json.decodeFromString<UserInfoResponse>(userInfoJson).sub
            }
                .getOrNull()
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
            if (
                budget.enabled != true &&
                    budget.used == null &&
                    budget.limit == null &&
                    budget.remaining == null
            ) {
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
    @Serializable(with = LenientDoubleOrNullSerializer::class) val autoPercentUsed: Double? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val apiPercentUsed: Double? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val totalPercentUsed: Double? = null,
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
    @Serializable(with = LenientDoubleOrNullSerializer::class) val autoPercentUsed: Double? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val apiPercentUsed: Double? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val totalPercentUsed: Double? = null,
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
    val isMerged: Boolean? = null,
)

@Serializable
private data class SandUsageResponse(
    @Serializable(with = LenientDoubleOrNullSerializer::class) val usagePercent: Double? = null,
    val nextResetTimestampUtc: String? = null,
    val includedLimitZero: Boolean? = null,
    val hasNonZeroIncludedLimit: Boolean? = null,
    val sandTrialExpiresAt: String? = null,
    val isUnified: Boolean? = null,
    val cursorPlanName: String? = null,
)

@Serializable
private data class UnifiedUsageResponse(
    val availability: String? = null,
    val monthlyUsage: UnifiedMonthlyUsageResponse? = null,
)

@Serializable
private data class UnifiedMonthlyUsageResponse(
    @Serializable(with = LenientDoubleOrNullSerializer::class) val usedCents: Double? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val limitCents: Double? = null,
    val periodStart: String? = null,
    val resetsAt: String? = null,
)

@Serializable
private data class PlanInfoDetails(
    val planName: String = "",
    val includedAmountCents: Double = 0.0,
    val price: String = "",
    val billingCycleEnd: String = "",
    /** A name such as INCLUDED_USAGE_PERIOD_WEEKLY, or the enum number. */
    @Serializable(with = LenientStringSerializer::class) val includedUsagePeriod: String? = null,
    @Serializable(with = LenientStringSerializer::class) val includedUsageResetsAt: String? = null,
)

/** Reads a string or a number as text, as proto JSON sends int64 and enum values either way. */
private object LenientStringSerializer : kotlinx.serialization.KSerializer<String?> {
    override val descriptor =
        kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
            "CursorLenientString",
            kotlinx.serialization.descriptors.PrimitiveKind.STRING,
        )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String? =
        ((decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement() as? JsonPrimitive)
            ?.contentOrNull

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String?) =
        encoder.encodeString(value.orEmpty())
}

@Serializable
private data class StripeProfileResponse(
    val membershipType: String = "",
    @SerialName("isTeamMember") val isTeamMember: Boolean = false,
)
