package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.YearMonth
import java.time.ZoneOffset

open class MistralQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val clock: () -> Instant = { Clock.System.now() },
) {
    open fun fetchQuota(cookieHeader: String, apiKey: String? = null): MistralQuota {
        val session = parseSessionCookies(cookieHeader)
        val now = clock()
        val month = YearMonth.now(ZoneOffset.UTC)
        val billingUrl = URI.create(
            "https://admin.mistral.ai/api/billing/v2/usage?month=${month.monthValue}&year=${month.year}",
        )
        val billingBody = getAdminJson(
            url = billingUrl,
            cookieHeader = session.cookieHeader,
            csrfToken = session.csrfToken,
            origin = "https://admin.mistral.ai",
            referer = "https://admin.mistral.ai/organization/usage",
        )
        val billing = parseBilling(billingBody)
        val vibeBody = session.csrfToken?.let { csrf ->
            runCatching {
                getAdminJson(
                    url = VIBE_USAGE_URI,
                    cookieHeader = session.consoleCookieHeader(),
                    csrfToken = csrf,
                    origin = "https://console.mistral.ai",
                    referer = "https://console.mistral.ai/",
                    csrfHeaderName = "X-CSRFToken",
                )
            }.getOrNull()
        }
        val vibe = vibeBody?.let(::parseVibeUsage)
        val monthly = monthlyWindow(vibe, billing)
        val identityBody = apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            runCatching { getJson(key, IDENTITY_URI) }.getOrNull()
        }
        val identity = identityBody?.let { runCatching { parseIdentity(it) }.getOrNull() }
        val probe = apiKey?.takeIf { it.isNotBlank() }?.let { key ->
            runCatching { probeRateLimits(key) }.getOrNull()
        }
        val tokenUsage = probe?.let { windowFromHeaders(it, HEADER_LIMIT_TOKENS, HEADER_REMAINING_TOKENS, now) }
        val requestUsage = probe?.let { windowFromHeaders(it, HEADER_LIMIT_REQUESTS, HEADER_REMAINING_REQUESTS, now) }
        val quota = MistralQuota(
            email = identity?.email.orEmpty(),
            organization = identity?.organization?.name.orEmpty(),
            workspace = identity?.workspace?.name.orEmpty(),
            apiKeyName = identity?.apiKey?.name.orEmpty(),
            monthlyUsage = monthly,
            tokenUsage = tokenUsage,
            requestUsage = requestUsage,
            fetchedAt = now,
        )
        quota.rawJson = buildRawResponse(billingBody, vibeBody, identityBody, probe?.let(::rateLimitHeaders))
        return quota
    }

    private fun probeRateLimits(apiKey: String): HttpHeaders {
        val request = HttpRequest.newBuilder()
            .uri(CHAT_URI)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(PROBE_BODY))
            .build()
        val response = send(request)
        val status = response.statusCode()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, response.body())
        }
        if (status !in 200..299 && status != 429) {
            throw MistralQuotaException("Request failed (HTTP $status). Try again later.", status, response.body())
        }
        return response.headers()
    }

    private fun getAdminJson(
        url: URI,
        cookieHeader: String,
        csrfToken: String?,
        origin: String,
        referer: String,
        csrfHeaderName: String = "X-CSRFTOKEN",
    ): String {
        val builder = HttpRequest.newBuilder()
            .uri(url)
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "*/*")
            .header("Cookie", cookieHeader)
            .header("Origin", origin)
            .header("Referer", referer)
            .header("User-Agent", BROWSER_USER_AGENT)
            .GET()
        if (!csrfToken.isNullOrBlank()) {
            builder.header(csrfHeaderName, csrfToken)
        }
        val response = send(builder.build())
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Paste a fresh Mistral admin cookie from settings.", status, body)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    private fun getJson(apiKey: String, endpoint: URI): String {
        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, body)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("Request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        private val IDENTITY_URI: URI = URI.create("https://api.mistral.ai/v1/users/me")
        private val CHAT_URI: URI = URI.create("https://api.mistral.ai/v1/chat/completions")
        private val VIBE_USAGE_URI: URI = URI.create(
            "https://console.mistral.ai/api-ui/trpc/billing.vibeUsage?batch=1&input=%7B%220%22%3A%7B%22json%22%3Anull%2C%22meta%22%3A%7B%22values%22%3A%5B%22undefined%22%5D%2C%22v%22%3A1%7D%7D%7D",
        )
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val HEADER_LIMIT_TOKENS = "x-ratelimit-limit-tokens-minute"
        private const val HEADER_REMAINING_TOKENS = "x-ratelimit-remaining-tokens-minute"
        private const val HEADER_LIMIT_REQUESTS = "x-ratelimit-limit-req-minute"
        private const val HEADER_REMAINING_REQUESTS = "x-ratelimit-remaining-req-minute"
        private const val MINUTE_MS = 60_000L
        private const val PROBE_BODY =
            """{"model":"mistral-small-latest","messages":[{"role":"user","content":"."}],"max_tokens":1}"""

        internal fun buildRawResponse(
            billingBody: String?,
            vibeBody: String?,
            identityBody: String?,
            rateLimits: Map<String, String>?,
        ): String {
            val session = buildJsonObject {
                jsonOrRaw(billingBody)?.let { put("billing", it) }
                jsonOrRaw(vibeBody)?.let { put("vibe", it) }
            }
            val apiKey = buildJsonObject {
                jsonOrRaw(identityBody)?.let { put("identity", it) }
                if (!rateLimits.isNullOrEmpty()) {
                    put("rate_limits", buildJsonObject {
                        rateLimits.forEach { (name, value) -> put(name, value) }
                    })
                }
            }
            return JsonSupport.json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    if (session.isNotEmpty()) put("session", session)
                    if (apiKey.isNotEmpty()) put("api_key", apiKey)
                },
            )
        }

        internal fun rateLimitHeaders(headers: HttpHeaders): Map<String, String> {
            return headers.map().entries
                .filter { it.key.startsWith("x-ratelimit-", ignoreCase = true) }
                .mapNotNull { entry ->
                    val value = entry.value.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    entry.key.lowercase() to value
                }
                .toMap()
        }

        private fun jsonOrRaw(body: String?): JsonElement? {
            val value = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { JsonSupport.json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
        }

        internal fun encodeStoredSession(sessionName: String, sessionValue: String, csrfToken: String?): String {
            val session = sessionFromFields(sessionName, sessionValue, csrfToken)
            return JsonSupport.json.encodeToString(
                MistralStoredSessionDto.serializer(),
                MistralStoredSessionDto(
                    sessionName = session.sessionPairs.first().first,
                    sessionValue = session.sessionPairs.first().second,
                    csrfToken = session.csrfToken.orEmpty(),
                ),
            )
        }

        internal fun storedSessionFields(raw: String?): MistralStoredSessionDto? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            decodeStoredSession(value)?.let { return it }
            return runCatching { parseSessionCookies(value) }.getOrNull()?.toStoredFields()
        }

        internal fun parseSessionCookies(raw: String): MistralSessionCookies {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) {
                throw MistralQuotaException("Mistral session cookie missing.")
            }
            decodeStoredSession(trimmed)?.let { stored ->
                return sessionFromFields(stored.sessionName, stored.sessionValue, stored.csrfToken)
            }
            val header = trimmed.removePrefix("Cookie:").trim()
            val pairs = header.split(';').mapNotNull { part ->
                val item = part.trim()
                val eq = item.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                item.substring(0, eq).trim() to stripCookieQuotes(item.substring(eq + 1).trim())
            }
            val sessionPairs = pairs.filter { it.first.startsWith("ory_session_") && it.second.isNotBlank() }
            if (sessionPairs.isEmpty()) {
                throw MistralQuotaException("Mistral cookie must include an ory_session_* name and value.")
            }
            val csrf = pairs.firstOrNull { it.first == "csrftoken" }?.second?.takeIf { it.isNotBlank() }
            val cookieHeader = pairs.joinToString("; ") { "${it.first}=${it.second}" }
            return MistralSessionCookies(cookieHeader = cookieHeader, csrfToken = csrf, sessionPairs = sessionPairs)
        }

        private fun sessionFromFields(sessionName: String, sessionValue: String, csrfToken: String?): MistralSessionCookies {
            val name = sessionName.trim()
            val value = stripCookieQuotes(sessionValue)
            if (!name.startsWith("ory_session_") || value.isBlank()) {
                throw MistralQuotaException("Need the ory_session_* cookie name and value from admin.mistral.ai.")
            }
            val csrf = csrfToken?.let(::stripCookieQuotes)?.takeIf { it.isNotBlank() }
            val pairs = buildList {
                add("$name=$value")
                csrf?.let { add("csrftoken=$it") }
            }
            return MistralSessionCookies(
                cookieHeader = pairs.joinToString("; "),
                csrfToken = csrf,
                sessionPairs = listOf(name to value),
            )
        }

        private fun decodeStoredSession(raw: String): MistralStoredSessionDto? {
            if (!raw.startsWith("{")) return null
            return runCatching { JsonSupport.json.decodeFromString<MistralStoredSessionDto>(raw) }.getOrNull()
                ?.takeIf { it.sessionName.isNotBlank() && it.sessionValue.isNotBlank() }
        }

        private fun stripCookieQuotes(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
        }

        internal fun parseVibeUsage(body: String): MistralVibeUsage? {
            val items = runCatching {
                JsonSupport.json.decodeFromString<List<MistralVibeBatchItemDto>>(body)
            }.getOrNull() ?: return null
            val json = items.firstOrNull()?.result?.data?.json ?: return null
            val percent = json.usagePercentage ?: return null
            if (!percent.isFinite() || percent !in 0.0..100.0) return null
            return MistralVibeUsage(percent, parseInstant(json.resetAt))
        }

        internal fun parseBilling(body: String): MistralBillingDto {
            return try {
                JsonSupport.json.decodeFromString<MistralBillingDto>(body)
            } catch (exception: Exception) {
                throw MistralQuotaException("Could not parse usage data.", 200, body, exception)
            }
        }

        internal fun monthlyWindow(vibe: MistralVibeUsage?, billing: MistralBillingDto): MistralUsageWindow? {
            val percent = vibe?.usagePercent
                ?: billing.vibeUsage?.takeIf { it.isFinite() && it in 0.0..100.0 }
                ?: return null
            val start = parseInstant(billing.startDate)
            val end = vibe?.resetsAt ?: parseInstant(billing.endDate)
            val periodMs = if (start != null && end != null && end.toEpochMilliseconds() > start.toEpochMilliseconds()) {
                end.toEpochMilliseconds() - start.toEpochMilliseconds()
            } else {
                Duration.ofDays(30).toMillis()
            }
            return MistralUsageWindow(
                usagePercent = percent,
                resetsAt = end,
                periodDurationMs = periodMs,
            )
        }

        private fun parseInstant(raw: String?): Instant? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.length == 10) {
                return runCatching { Instant.parse("${value}T00:00:00Z") }.getOrNull()
            }
            return runCatching { Instant.parse(value) }.getOrNull()
        }

        internal fun parseIdentity(body: String): MistralIdentityDto {
            return try {
                JsonSupport.json.decodeFromString<MistralIdentityDto>(body)
            } catch (exception: Exception) {
                throw MistralQuotaException("Could not parse usage data.", 200, body, exception)
            }
        }

        internal fun windowFromHeaders(
            headers: HttpHeaders,
            limitName: String,
            remainingName: String,
            now: Instant,
        ): MistralUsageWindow? {
            val limit = headerLong(headers, limitName) ?: return null
            val remaining = headerLong(headers, remainingName) ?: return null
            if (limit <= 0L) return null
            val used = (limit - remaining).coerceAtLeast(0L)
            return MistralUsageWindow(
                used = used,
                limit = limit,
                remaining = remaining.coerceAtLeast(0L),
                usagePercent = used.toDouble() / limit.toDouble() * 100.0,
                resetsAt = nextMinute(now),
                periodDurationMs = MINUTE_MS,
            )
        }

        internal fun windowFromValues(limit: Long, remaining: Long, now: Instant): MistralUsageWindow? {
            if (limit <= 0L) return null
            val used = (limit - remaining).coerceAtLeast(0L)
            return MistralUsageWindow(
                used = used,
                limit = limit,
                remaining = remaining.coerceAtLeast(0L),
                usagePercent = used.toDouble() / limit.toDouble() * 100.0,
                resetsAt = nextMinute(now),
                periodDurationMs = MINUTE_MS,
            )
        }

        private fun headerLong(headers: HttpHeaders, name: String): Long? {
            return headers.firstValue(name).orElse(null)?.trim()?.toLongOrNull()
        }

        private fun nextMinute(now: Instant): Instant {
            val remainder = now.toEpochMilliseconds() % MINUTE_MS
            val delta = if (remainder == 0L) MINUTE_MS else MINUTE_MS - remainder
            return Instant.fromEpochMilliseconds(now.toEpochMilliseconds() + delta)
        }
    }
}

@Serializable
internal data class MistralIdentityDto(
    val id: String? = null,
    val email: String? = null,
    val workspace: MistralNamedIdDto? = null,
    val organization: MistralNamedIdDto? = null,
    @SerialName("api_key") val apiKey: MistralNamedIdDto? = null,
)

@Serializable
internal data class MistralNamedIdDto(
    val id: String? = null,
    val name: String? = null,
)

internal data class MistralSessionCookies(
    val cookieHeader: String,
    val csrfToken: String?,
    val sessionPairs: List<Pair<String, String>>,
) {
    fun consoleCookieHeader(): String {
        val pairs = buildList {
            csrfToken?.let { add("csrftoken=$it") }
            sessionPairs.forEach { add("${it.first}=${it.second}") }
        }
        return pairs.joinToString("; ")
    }

    fun toStoredFields(): MistralStoredSessionDto {
        val session = sessionPairs.first()
        return MistralStoredSessionDto(session.first, session.second, csrfToken.orEmpty())
    }
}

@Serializable
internal data class MistralStoredSessionDto(
    val sessionName: String = "",
    val sessionValue: String = "",
    val csrfToken: String = "",
)

internal data class MistralVibeUsage(
    val usagePercent: Double,
    val resetsAt: Instant?,
)

@Serializable
internal data class MistralBillingDto(
    @SerialName("vibe_usage")
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val vibeUsage: Double? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

@Serializable
private data class MistralVibeBatchItemDto(
    val result: MistralVibeResultDto? = null,
)

@Serializable
private data class MistralVibeResultDto(
    val data: MistralVibeDataDto? = null,
)

@Serializable
private data class MistralVibeDataDto(
    val json: MistralVibeJsonDto? = null,
)

@Serializable
private data class MistralVibeJsonDto(
    @SerialName("usage_percentage")
    @Serializable(with = LenientDoubleOrNullSerializer::class)
    val usagePercentage: Double? = null,
    @SerialName("reset_at") val resetAt: String? = null,
)
