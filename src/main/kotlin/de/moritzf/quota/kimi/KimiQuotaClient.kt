package de.moritzf.quota.kimi

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

open class KimiQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    /** Overrides the login region's endpoints, for tests. */
    private val usageEndpoint: URI? = null,
    tokenEndpoint: URI? = null,
    deviceId: String = KimiDeviceHeaders.processDeviceId,
) {
    private val credentialRefresher = KimiCredentialRefresher(httpClient, tokenEndpoint, deviceId)

    open fun fetchQuota(credentials: KimiCredentials): KimiFetchResult {
        var usableCredentials = credentialRefresher.refreshIfNeeded(credentials)
        var response =
            fetchQuotaResponse(
                usableCredentials.accessToken.ifBlank {
                    throw KimiQuotaException("Kimi login required. Log in from settings.")
                },
                usableCredentials.kimiRegion,
            )
        if (response.statusCode().isUnauthorized()) {
            usableCredentials =
                credentialRefresher.refresh(usableCredentials)
                    ?: throw KimiQuotaException(
                        "Session expired. Log in to Kimi again from settings.",
                        response.statusCode(),
                        response.body(),
                    )
            response =
                fetchQuotaResponse(
                    usableCredentials.accessToken.ifBlank {
                        throw KimiQuotaException("Kimi login required. Log in from settings.")
                    },
                    usableCredentials.kimiRegion,
                )
        }

        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException(
                "Session expired. Log in to Kimi again from settings.",
                status,
                body,
            )
        }
        if (status !in 200..299) {
            throw KimiQuotaException(
                "Request failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        val quota = parseQuota(body)
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return KimiFetchResult(quota, usableCredentials)
    }

    private fun fetchQuotaResponse(accessToken: String, region: KimiRegion): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder()
                .uri(usageEndpoint ?: region.api("usages"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .GET()
                .build()
        return send(request)
    }

    private fun Int.isUnauthorized(): Boolean = this == 401 || this == 403

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw KimiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KimiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        private val FIVE_HOURS = Duration.ofHours(5).toMillis()
        private val ONE_WEEK = Duration.ofDays(7).toMillis()
        private val RESET_TOLERANCE = 2.seconds

        fun parseQuota(body: String): KimiQuota {
            val dto =
                try {
                    JsonSupport.json.decodeFromString<KimiUsageResponseDto>(body)
                } catch (exception: Exception) {
                    throw KimiQuotaException("Could not parse usage data.", 200, body, exception)
                }
            val plan = normalizeMembership(dto.user?.membership?.level)
            val sessionLimit =
                dto.limits.firstOrNull {
                    it.window?.duration == 300L && it.window.timeUnit == "TIME_UNIT_MINUTE"
                } ?: dto.limits.firstOrNull()
            val sessionCounter =
                sessionLimit?.detail?.toCounter(sessionLimit.window?.durationMillis() ?: FIVE_HOURS)
            // Migrated plans use ratios. The counters that can still accompany them are used only
            // where they show the same window more exhausted, which the ratio sometimes misses.
            dto.usages?.let { usages ->
                fun window(key: String, durationMs: Long? = null): KimiUsageWindow? {
                    val entry =
                        JsonSupport.decodeSectionOrNull(usages[key], KimiRatioUsageDto.serializer())
                            ?: return null
                    val ratio = entry.usedRatio?.takeIf { it >= 0.0 } ?: return null
                    return KimiUsageWindow(
                        usagePercent = ratio.coerceAtMost(1.0) * 100.0,
                        resetsAt = parseInstant(entry.resetTime),
                        periodDurationMs = durationMs,
                    )
                }
                return KimiQuota(
                    plan = plan,
                    sessionUsage = reconcile(window("limit_5h", FIVE_HOURS), sessionCounter),
                    weeklyUsage =
                        reconcile(window("limit_7d", ONE_WEEK), dto.usage?.toCounter(ONE_WEEK)),
                    monthlyUsage = window("limit_month_total"),
                    monthlyCodeUsage = window("limit_month_code"),
                    extraUsage = extraUsage(dto.boosterWallet),
                )
            }
            return KimiQuota(
                plan = plan,
                sessionUsage = sessionCounter?.window,
                totalUsage = dto.usage?.toCounter(null)?.window,
                extraUsage = extraUsage(dto.boosterWallet),
            )
        }

        /**
         * The booster wallet counts only as a funded BOOSTER balance. Wallet amounts are
         * fixed-point with 1e6 units per cent; monthly prices are whole cents.
         */
        private fun extraUsage(wallet: JsonObject?): KimiExtraUsage? {
            val balance = wallet?.get("balance") as? JsonObject ?: return null
            if ((balance["type"] as? JsonPrimitive)?.contentOrNull != "BOOSTER") return null
            val total = fixedPointCents(balance["amount"])?.takeIf { it > 0 } ?: return null
            fun price(key: String): JsonObject? = wallet[key] as? JsonObject
            fun cents(price: JsonObject?): Long? =
                price
                    ?.get("priceInCents")
                    ?.lenientDoubleOrNull()
                    ?.takeIf { it.isFinite() }
                    ?.toLong()
            fun currency(price: JsonObject?): String? =
                (price?.get("currency") as? JsonPrimitive)?.contentOrNull?.takeIf {
                    it.isNotBlank()
                }
            val limit = price("monthlyChargeLimit")
            val used = price("monthlyUsed")
            return KimiExtraUsage(
                    balanceCents = fixedPointCents(balance["amountLeft"]) ?: 0,
                    usedCents = cents(used) ?: 0,
                    limitCents = cents(limit),
                    limitEnabled =
                        (wallet["monthlyChargeLimitEnabled"] as? JsonPrimitive)?.booleanOrNull ==
                            true,
                    currency = currency(limit) ?: currency(used) ?: "USD",
                )
                .takeIf { total > 0 }
        }

        private fun fixedPointCents(value: JsonElement?): Long? {
            val raw =
                value?.lenientDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: return null
            val cents = raw / 1_000_000.0
            return if (cents > 0 && cents < 1) 1 else kotlin.math.round(cents).toLong()
        }

        /** A counted window, and whether its counts could be read. */
        private data class Counter(val window: KimiUsageWindow, val reliable: Boolean)

        /**
         * The counter replaces the ratio only for the same window: equal length, readable counts, a
         * higher percentage, and resets at most two seconds apart.
         */
        private fun reconcile(ratio: KimiUsageWindow?, counter: Counter?): KimiUsageWindow? {
            if (ratio == null || counter == null || !counter.reliable) return ratio
            val counted = counter.window
            if (counted.periodDurationMs != ratio.periodDurationMs) return ratio
            if (counted.usagePercent.coerceIn(0.0, 100.0) <= ratio.usagePercent) return ratio
            val ratioReset = ratio.resetsAt ?: return ratio
            val countedReset = counted.resetsAt ?: return ratio
            if ((ratioReset - countedReset).absoluteValue > RESET_TOLERANCE) return ratio
            return counted
        }

        /**
         * Used is the reported count, otherwise limit minus remaining. Without either the counts
         * are unreliable and show no usage rather than a full window.
         */
        private fun KimiLimitDetailDto.toCounter(durationMs: Long?): Counter? {
            val limitValue = limit?.lenientDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            val resetsAt = parseInstant(resetTime ?: resetAt ?: resetTimeSnake ?: resetAtSnake)
            if (limitValue == null) {
                return Counter(KimiUsageWindow(resetsAt = resetsAt), reliable = false)
            }
            val usedValue =
                used?.lenientDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                    ?: remaining
                        ?.lenientDoubleOrNull()
                        ?.takeIf { it.isFinite() && it in 0.0..limitValue }
                        ?.let { limitValue - it }
                    ?: return Counter(
                        KimiUsageWindow(limit = limitValue.toLong(), resetsAt = resetsAt),
                        reliable = false,
                    )
            return Counter(
                KimiUsageWindow(
                    used = usedValue.toLong(),
                    limit = limitValue.toLong(),
                    usagePercent = usedValue / limitValue * 100.0,
                    resetsAt = resetsAt,
                    periodDurationMs = durationMs,
                ),
                reliable = true,
            )
        }

        private fun parseInstant(value: String?): Instant? = value?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }

        private fun KimiWindowDto.durationMillis(): Long? {
            return when (timeUnit) {
                "TIME_UNIT_MINUTE" -> duration?.times(60_000L)
                "TIME_UNIT_HOUR" -> duration?.times(3_600_000L)
                "TIME_UNIT_DAY" -> duration?.times(86_400_000L)
                else -> null
            }
        }

        private fun normalizeMembership(level: String?): String {
            return when (level) {
                "LEVEL_INTERMEDIATE" -> "Kimi Code Intermediate"
                "LEVEL_ADVANCED" -> "Kimi Code Advanced"
                "LEVEL_PREMIUM" -> "Kimi Code Premium"
                null,
                "" -> "Kimi Code"
                else ->
                    level
                        .removePrefix("LEVEL_")
                        .lowercase()
                        .replaceFirstChar { it.uppercase() }
                        .let { "Kimi Code $it" }
            }
        }
    }
}

data class KimiFetchResult(
    val quota: KimiQuota,
    val credentials: KimiCredentials,
)

@Serializable
private data class KimiUsageResponseDto(
    val usages: JsonObject? = null,
    val usage: KimiLimitDetailDto? = null,
    val limits: List<KimiLimitDto> = emptyList(),
    val user: KimiUserDto? = null,
    val boosterWallet: JsonObject? = null,
)

@Serializable
private data class KimiRatioUsageDto(
    @SerialName("used_ratio")
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val usedRatio: Double? = null,
    @SerialName("reset_time") val resetTime: String? = null,
)

@Serializable
private data class KimiLimitDto(
    val window: KimiWindowDto? = null,
    val detail: KimiLimitDetailDto? = null,
)

@Serializable
private data class KimiWindowDto(
    val duration: Long? = null,
    val timeUnit: String? = null,
)

/** Counts arrive as numbers or numeric strings; the reset under several names. */
@Serializable
private data class KimiLimitDetailDto(
    val limit: JsonElement? = null,
    val used: JsonElement? = null,
    val remaining: JsonElement? = null,
    val resetTime: String? = null,
    val resetAt: String? = null,
    @SerialName("reset_time") val resetTimeSnake: String? = null,
    @SerialName("reset_at") val resetAtSnake: String? = null,
)

@Serializable private data class KimiUserDto(val membership: KimiMembershipDto? = null)

@Serializable private data class KimiMembershipDto(val level: String? = null)
