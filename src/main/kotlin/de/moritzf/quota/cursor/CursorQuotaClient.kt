package de.moritzf.quota.cursor

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * HTTP client for fetching Cursor subscription usage from the dashboard API.
 */
open class CursorQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    @Throws(IOException::class, InterruptedException::class)
    open fun fetchQuota(accessToken: String, auth: CursorAuth? = null): CursorQuota {
        require(accessToken.isNotBlank()) { "accessToken must not be null or blank" }

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

        internal fun buildRawJson(periodUsageJson: String, planInfoJson: String?, profileJson: String?): String {
            return buildJsonObject {
                put("periodUsage", periodUsageJson)
                planInfoJson?.let { put("planInfo", it) }
                profileJson?.let { put("profile", it) }
            }.toString()
        }

        internal fun normalizeRawJson(json: String): String {
            return runCatching {
                val root = JsonSupport.json.parseToJsonElement(json).jsonObject
                val needsNormalization = listOf("periodUsage", "planInfo", "profile").any { key ->
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
    }
}

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
