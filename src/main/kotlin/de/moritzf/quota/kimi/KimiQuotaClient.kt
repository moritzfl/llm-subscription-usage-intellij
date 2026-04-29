package de.moritzf.quota.kimi

import de.moritzf.quota.shared.JsonSupport
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

open class KimiQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val usageEndpoint: URI = USAGE_ENDPOINT,
    private val tokenEndpoint: URI = TOKEN_ENDPOINT,
) {
    open fun fetchQuota(credentials: KimiCredentials): KimiFetchResult {
        val usableCredentials = refreshIfNeeded(credentials)
        val accessToken = usableCredentials.accessToken.ifBlank {
            throw KimiQuotaException("Kimi login required. Log in from settings.")
        }

        val request = HttpRequest.newBuilder()
            .uri(usageEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException("Session expired. Log in to Kimi again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw KimiQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        val quota = parseQuota(body)
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return KimiFetchResult(quota, usableCredentials)
    }

    private fun refreshIfNeeded(credentials: KimiCredentials): KimiCredentials {
        val expiresAt = credentials.expiresAtEpochSeconds
        if (credentials.accessToken.isNotBlank() && (expiresAt == null || expiresAt - Clock.System.now().epochSeconds > REFRESH_BUFFER_SECONDS)) {
            return credentials
        }
        val refreshToken = credentials.refreshToken.ifBlank { return credentials }
        return refreshCredentials(refreshToken)
    }

    private fun refreshCredentials(refreshToken: String): KimiCredentials {
        val form = formEncode(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        val builder = HttpRequest.newBuilder()
            .uri(tokenEndpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
        KimiDeviceHeaders.all().forEach { (key, value) -> builder.header(key, value) }
        val request = builder.POST(HttpRequest.BodyPublishers.ofString(form)).build()
        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw KimiQuotaException("Session expired. Log in to Kimi again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw KimiQuotaException("Token refresh failed (HTTP $status). Log in to Kimi again.", status, body)
        }
        val dto = try {
            JsonSupport.json.decodeFromString<KimiTokenResponseDto>(body)
        } catch (exception: Exception) {
            throw KimiQuotaException("Could not parse Kimi token response.", status, body, exception)
        }
        return KimiCredentials(
            accessToken = dto.accessToken.orEmpty(),
            refreshToken = dto.refreshToken ?: refreshToken,
            expiresAtEpochSeconds = dto.expiresIn?.let { Clock.System.now().epochSeconds + it.toDouble() },
            scope = dto.scope,
            tokenType = dto.tokenType ?: "Bearer",
        )
    }

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
        private val USAGE_ENDPOINT = URI.create("https://api.kimi.com/coding/v1/usages")
        private val TOKEN_ENDPOINT = URI.create("https://auth.kimi.com/api/oauth/token")
        private const val CLIENT_ID = "17e5f671-d194-4dfb-9706-5516cb48c098"
        private const val REFRESH_BUFFER_SECONDS = 300L

        fun parseQuota(body: String): KimiQuota {
            val dto = try {
                JsonSupport.json.decodeFromString<KimiUsageResponseDto>(body)
            } catch (exception: Exception) {
                throw KimiQuotaException("Could not parse usage data.", 200, body, exception)
            }
            val total = dto.usage?.toWindow(null)
            val sessionLimit = dto.limits.firstOrNull { it.window?.duration == 300L && it.window.timeUnit == "TIME_UNIT_MINUTE" }
                ?: dto.limits.firstOrNull()
            val session = sessionLimit?.detail?.toWindow(sessionLimit.window?.durationMillis())
            return KimiQuota(
                plan = normalizeMembership(dto.user?.membership?.level),
                sessionUsage = session,
                totalUsage = total,
            )
        }

        private fun KimiLimitDetailDto.toWindow(durationMs: Long?): KimiUsageWindow {
            val limitValue = limit?.toLongOrNull() ?: 0
            val remainingValue = remaining?.toLongOrNull() ?: 0
            val used = (limitValue - remainingValue).coerceAtLeast(0)
            return KimiUsageWindow(
                used = used,
                limit = limitValue,
                usagePercent = if (limitValue > 0) used.toDouble() / limitValue.toDouble() * 100.0 else 0.0,
                resetsAt = resetTime?.let { runCatching { Instant.parse(it) }.getOrNull() },
                periodDurationMs = durationMs,
            )
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
                null, "" -> "Kimi Code"
                else -> level.removePrefix("LEVEL_").lowercase().replaceFirstChar { it.uppercase() }.let { "Kimi Code $it" }
            }
        }

        private fun formEncode(vararg pairs: Pair<String, String>): String {
            return pairs.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
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
    val usage: KimiLimitDetailDto? = null,
    val limits: List<KimiLimitDto> = emptyList(),
    val user: KimiUserDto? = null,
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

@Serializable
private data class KimiLimitDetailDto(
    val limit: String? = null,
    val remaining: String? = null,
    val resetTime: String? = null,
)

@Serializable
private data class KimiUserDto(val membership: KimiMembershipDto? = null)

@Serializable
private data class KimiMembershipDto(val level: String? = null)

@Serializable
private data class KimiTokenResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val scope: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
)
