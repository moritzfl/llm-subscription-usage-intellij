package de.moritzf.quota.claude

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.LenientDoubleOrNullSerializer
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

open class ClaudeQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val usageUri: URI = DEFAULT_USAGE_URI,
) {
    open fun fetchQuota(accessToken: String?): ClaudeQuota {
        val token =
            accessToken?.trim()?.takeIf { it.isNotBlank() }
                ?: throw ClaudeQuotaException("Claude login required. Log in from Claude settings.")

        // One request per refresh: the `cedar_ember` read returns the usage windows together with
        // the resets on offer. A server that rejects the flag gets the plain read instead.
        val body =
            try {
                getUsageJson(token, usageQuery(RESETS_QUERY))
            } catch (exception: ClaudeQuotaException) {
                if (exception.statusCode != 400) throw exception
                getUsageJson(token, usageUri)
            }
        val quota =
            try {
                parseQuota(body)
            } catch (exception: ClaudeQuotaException) {
                throw ClaudeQuotaException(
                    exception.message ?: "Claude usage response changed.",
                    200,
                    body,
                    exception,
                )
            } catch (exception: Exception) {
                throw ClaudeQuotaException("Claude usage response changed.", 200, body, exception)
            }
        return quota.copy(resets = parseResets(body)).also { it.rawJson = body }
    }

    /**
     * The read Claude Code makes at a usage limit, which alone reports the weekly session reset.
     * Returns null when nothing is on offer.
     */
    open fun fetchAtLimitResets(accessToken: String): ClaudeResets? =
        parseResets(getUsageJson(accessToken, usageQuery(AT_LIMIT_QUERY)))

    /** The login's organization, which reset claims are addressed to. */
    open fun fetchOrganizationUuid(accessToken: String): String {
        val request = authorized(usageUri.resolve(PROFILE_PATH), accessToken).GET().build()
        val body = checked(send(request), "profile")
        val organization =
            runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        val uuid =
            ((organization?.get("organization") as? JsonObject)?.get("uuid") as? JsonPrimitive)
                ?.contentOrNull
        return uuid?.takeIf(ORGANIZATION_UUID::matches)
            ?: throw ClaudeQuotaException("Claude profile has no organization.", 200, body)
    }

    private fun usageQuery(query: String): URI =
        URI.create("${usageUri.toString().substringBefore('?')}?$query")

    /** Uses one reset. It cannot be undone once the server answers `reset`. */
    open fun redeemReset(
        accessToken: String,
        organizationUuid: String,
        choice: ClaudeResetChoice,
    ): ClaudeResetOutcome {
        require(ORGANIZATION_UUID.matches(organizationUuid)) { "Invalid Claude organization id" }
        val claim =
            when (choice) {
                is ClaudeResetChoice.Session -> ResetClaimDto(SESSION_RESET_PROGRAM)
                is ClaudeResetChoice.Grant -> {
                    require(GRANT_ID.matches(choice.grant.id)) { "Invalid Claude reset id" }
                    ResetClaimDto(OFFER_PROGRAM, choice.grant.id, UUID.randomUUID().toString())
                }
            }
        val request =
            authorized(
                    usageUri.resolve("/api/organizations/$organizationUuid/reset_rate_limits"),
                    accessToken,
                )
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        JsonSupport.json.encodeToString(ResetClaimDto.serializer(), claim)
                    )
                )
                .build()
        val response = send(request)
        if (response.statusCode() == 429) {
            return ClaudeResetOutcome(ClaudeResetOutcome.RATE_LIMITED)
        }
        val body = checked(response, "reset")
        val answer =
            JsonSupport.decodeSectionOrNull(
                runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull(),
                ResetClaimResultDto.serializer(),
            ) ?: throw ClaudeQuotaException("Claude reset response changed.", 200, body)
        return ClaudeResetOutcome(answer.result ?: "unavailable")
    }

    private fun authorized(uri: URI, accessToken: String): HttpRequest.Builder =
        HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("anthropic-beta", OAUTH_BETA)
            .header("User-Agent", USER_AGENT)

    private fun checked(response: HttpResponse<String>, action: String): String {
        val status = response.statusCode()
        val body = response.body()
        when {
            status == 401 || status == 403 ->
                throw ClaudeQuotaException(
                    "Claude rejected the $action request (HTTP $status).",
                    status,
                    body,
                )
            status == 429 ->
                throw ClaudeQuotaException(
                    "Claude rate limited the $action request. Try again in a minute.",
                    status,
                    body,
                    retryAfter = retryAfter(response),
                )
            status !in 200..299 ->
                throw ClaudeQuotaException(
                    "Claude $action request failed (HTTP $status).",
                    status,
                    body,
                )
        }
        return body
    }

    private fun getUsageJson(accessToken: String, uri: URI): String {
        val request = authorized(uri, accessToken).GET().build()

        val response = send(request)
        val status = response.statusCode()
        val body = response.body()
        if (status == 401) {
            throw ClaudeQuotaException(
                "Claude usage API rejected the access token (HTTP 401).",
                status,
                body,
            )
        }
        if (status == 403) {
            if (body.contains("user:profile", ignoreCase = true)) {
                throw ClaudeQuotaException(
                    "Claude token is missing the user:profile scope required for usage. Log in again from Claude settings.",
                    status,
                    body,
                )
            }
            throw ClaudeQuotaException("Claude usage API denied access (HTTP 403).", status, body)
        }
        if (status == 429) {
            throw ClaudeQuotaException(
                "Claude usage API rate limited.",
                status,
                body,
                retryAfter = retryAfter(response),
            )
        }
        if (status !in 200..299) {
            throw ClaudeQuotaException(
                "Claude usage request failed (HTTP $status). Try again later.",
                status,
                body,
            )
        }
        return body
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: HttpTimeoutException) {
            throw ClaudeQuotaException(
                "Claude usage request timed out. Try again later.",
                0,
                null,
                exception,
            )
        } catch (exception: IOException) {
            throw ClaudeQuotaException(
                "Claude usage request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ClaudeQuotaException(
                "Claude usage request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }
    }

    companion object {
        @JvmField
        val DEFAULT_USAGE_URI: URI = URI.create("https://api.anthropic.com/api/oauth/usage")

        private const val OAUTH_BETA = "oauth-2025-04-20"
        private const val USER_AGENT = "claude-cli/2.1.87 (external, cli)"
        private const val PROFILE_PATH = "/api/oauth/profile"
        private const val RESETS_QUERY = "cedar_ember=1"
        private const val AT_LIMIT_QUERY = "at_wall=1&skip_spend=1"
        private const val OFFER_PROGRAM = "cedar_ember"
        private const val SESSION_RESET_PROGRAM = "juniper_tide"
        private val GRANT_ID = Regex("^[a-z0-9_-]{1,40}$")
        private val ORGANIZATION_UUID =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val FIVE_HOUR_MS = Duration.ofHours(5).toMillis()
        private val SEVEN_DAY_MS = Duration.ofDays(7).toMillis()

        /**
         * Resets on offer in a usage response read with `cedar_ember=1` or `at_wall=1`. Returns
         * null when neither block offers anything. A malformed grant is dropped on its own.
         */
        fun parseResets(json: String): ClaudeResets? {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(json) }.getOrNull() as? JsonObject
                    ?: return null
            val offers =
                JsonSupport.decodeSectionOrNull(root["cedar_ember"], ResetOffersDto.serializer())
                    ?.takeIf { it.eligible == true }
            val session =
                JsonSupport.decodeSectionOrNull(root["juniper_tide"], SessionResetDto.serializer())
                    ?.takeIf { it.arm == "reset" && it.available == true }
            val grants =
                JsonSupport.decodeListItemsLeniently(offers?.grants, ResetGrantDto.serializer())
                    .mapNotNull { it.toGrant() }
            if (grants.isEmpty() && session == null) return null
            return ClaudeResets(
                grants = grants,
                nextGrantId = offers?.nextGrantId?.takeIf { id -> grants.any { it.id == id } },
                sessionReset =
                    session?.let { ClaudeSessionReset(parseInstantOrNull(it.weeklyResetsAt)) },
            )
        }

        /** Seconds or an HTTP date, as `Retry-After` allows; null when absent or unreadable. */
        internal fun retryAfter(
            response: HttpResponse<*>,
            now: java.time.Instant = java.time.Instant.now(),
        ): Duration? {
            val raw = response.headers().firstValue("Retry-After").orElse(null)?.trim()
            if (raw.isNullOrEmpty()) return null
            raw.toLongOrNull()?.let { seconds ->
                return Duration.ofSeconds(seconds).takeUnless { it.isNegative }
            }
            val date =
                runCatching {
                    java.time.ZonedDateTime.parse(
                        raw,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                    )
                }
                    .getOrNull() ?: return null
            return Duration.between(now, date.toInstant()).takeUnless { it.isNegative }
        }

        private fun ResetGrantDto.toGrant(): ClaudeResetGrant? {
            val grantId = id?.takeIf(GRANT_ID::matches) ?: return null
            return ClaudeResetGrant(
                id = grantId,
                label = label.orEmpty().trim(),
                resetsLeft = (resetsLeft ?: 0).coerceAtLeast(0),
                endsAt = parseInstantOrNull(endsAt),
                paused = paused == true,
                usableNow = usableNow == true,
                clears =
                    (clears as? JsonArray).orEmpty().mapNotNull {
                        (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    },
            )
        }

        private fun parseInstantOrNull(value: String?): Instant? = value?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }

        fun parseQuota(
            usageJson: String,
            fetchedAt: Instant = Clock.System.now(),
        ): ClaudeQuota {
            // Sections are decoded individually so one unparsable block (for example a reshaped
            // extra_usage or a broken limits entry) only drops that block, not the whole quota.
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(usageJson) }.getOrNull()
                    as? JsonObject
                    ?: throw ClaudeQuotaException("Claude usage response changed.", 200, usageJson)

            fun window(key: String): ClaudeUsageWindowDto? =
                JsonSupport.decodeSectionOrNull(root[key], ClaudeUsageWindowDto.serializer())

            val payload =
                ClaudeUsageResponseDto(
                    fiveHour = window("five_hour"),
                    sevenDay = window("seven_day"),
                    sevenDaySonnet = window("seven_day_sonnet"),
                    sevenDayOpus = window("seven_day_opus"),
                    sevenDayOauthApps = window("seven_day_oauth_apps"),
                    sevenDayRoutines = window("seven_day_routines"),
                    sevenDayClaudeRoutines = window("seven_day_claude_routines"),
                    claudeRoutines = window("claude_routines"),
                    routines = window("routines"),
                    routine = window("routine"),
                    sevenDayCowork = window("seven_day_cowork"),
                    cowork = window("cowork"),
                    extraUsage =
                        JsonSupport.decodeSectionOrNull(
                            root["extra_usage"],
                            ClaudeExtraUsageDto.serializer(),
                        ),
                    limits =
                        JsonSupport.decodeListItemsLeniently(
                            root["limits"],
                            ClaudeLimitDto.serializer(),
                        ),
                )

            val fiveHour = payload.fiveHour.toWindow("5-hour", FIVE_HOUR_MS)
            val sevenDay = payload.sevenDay.toWindow("7-day", SEVEN_DAY_MS)
            val sevenDaySonnet = payload.sevenDaySonnet.toWindow("7-day Sonnet", SEVEN_DAY_MS)
            val sevenDayOpus = payload.sevenDayOpus.toWindow("7-day Opus", SEVEN_DAY_MS)
            val sevenDayOauthApps =
                payload.sevenDayOauthApps.toWindow("7-day OAuth apps", SEVEN_DAY_MS)
            val routines = firstRoutinesWindow(payload)
            val scopedLimits = scopedLimitWindows(payload)
            val extraUsage = payload.extraUsage?.toExtraUsage()

            val hasWindows =
                listOfNotNull(
                        fiveHour,
                        sevenDay,
                        sevenDaySonnet,
                        sevenDayOpus,
                        sevenDayOauthApps,
                        routines,
                    )
                    .isNotEmpty() || scopedLimits.isNotEmpty()
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
                val scopeLabel =
                    limit.scope?.let { scope ->
                        scope.model?.displayName?.takeIf { it.isNotBlank() }
                            ?: scope.model?.id?.takeIf { it.isNotBlank() }
                            ?: scope.surfaceName?.takeIf { it.isNotBlank() }
                    } ?: return@mapNotNull null
                val group = limit.group?.takeIf { it.isNotBlank() } ?: limit.kind
                val groupLabel =
                    when (group?.lowercase()) {
                        "session" -> "Session"
                        "weekly" -> "Weekly"
                        else -> group?.replaceFirstChar { it.uppercase() } ?: "Limit"
                    }
                val periodDurationMs =
                    when (group?.lowercase()) {
                        "session" -> FIVE_HOUR_MS
                        "weekly" -> SEVEN_DAY_MS
                        else -> null
                    }
                ClaudeUsageWindow(
                    label = "$groupLabel ($scopeLabel)",
                    usagePercent = percent.coerceIn(0.0, 100.0),
                    resetsAt =
                        limit.resetsAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    periodDurationMs = periodDurationMs,
                )
            }
        }

        private fun firstRoutinesWindow(payload: ClaudeUsageResponseDto): ClaudeUsageWindow? {
            val candidates =
                listOf(
                    payload.sevenDayRoutines,
                    payload.sevenDayClaudeRoutines,
                    payload.claudeRoutines,
                    payload.routines,
                    payload.routine,
                    payload.sevenDayCowork,
                    payload.cowork,
                )
            for (candidate in candidates) {
                candidate.toWindow("Daily Routines", SEVEN_DAY_MS)?.let {
                    return it
                }
            }
            return null
        }

        private fun ClaudeUsageWindowDto?.toWindow(
            label: String,
            periodDurationMs: Long,
        ): ClaudeUsageWindow? {
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
            val percent =
                utilization
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
    @SerialName("seven_day_claude_routines")
    val sevenDayClaudeRoutines: ClaudeUsageWindowDto? = null,
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
    @Serializable(with = LenientDoubleOrNullSerializer::class) val utilization: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
)

@Serializable
private data class ClaudeLimitDto(
    val kind: String? = null,
    val group: String? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val percent: Double? = null,
    @SerialName("resets_at") val resetsAt: String? = null,
    val scope: ClaudeLimitScopeDto? = null,
)

@Serializable
private data class ClaudeLimitScopeDto(
    val model: ClaudeLimitModelDto? = null,
    /** `{"display_name": …}` in current responses; a plain string in older ones. */
    val surface: JsonElement? = null,
) {
    val surfaceName: String?
        get() =
            when (val value = surface) {
                is JsonObject ->
                    (value["display_name"] as? JsonPrimitive)?.contentOrNull
                        ?: (value["id"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> value.takeIf { it.isString }?.contentOrNull
                else -> null
            }
}

@Serializable
private data class ClaudeLimitModelDto(
    val id: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

@Serializable
private data class ClaudeExtraUsageDto(
    @SerialName("is_enabled") val isEnabled: Boolean? = null,
    @SerialName("monthly_limit")
    @Serializable(with = LenientCreditsSerializer::class)
    val monthlyLimit: Long? = null,
    @SerialName("used_credits")
    @Serializable(with = LenientCreditsSerializer::class)
    val usedCredits: Long? = null,
    @Serializable(with = LenientDoubleOrNullSerializer::class) val utilization: Double? = null,
    val currency: String? = null,
)

@Serializable
private data class ResetOffersDto(
    val eligible: Boolean? = null,
    val grants: JsonElement? = null,
    @SerialName("next_grant_id") val nextGrantId: String? = null,
)

@Serializable
private data class ResetGrantDto(
    val id: String? = null,
    val label: String? = null,
    @SerialName("resets_left") val resetsLeft: Int? = null,
    @SerialName("ends_at") val endsAt: String? = null,
    val paused: Boolean? = null,
    @SerialName("usable_now") val usableNow: Boolean? = null,
    val clears: JsonElement? = null,
)

@Serializable
private data class SessionResetDto(
    val arm: String? = null,
    val available: Boolean? = null,
    @SerialName("weekly_resets_at") val weeklyResetsAt: String? = null,
)

@Serializable
private data class ResetClaimDto(
    val program: String,
    @SerialName("grant_id") val grantId: String? = null,
    @SerialName("request_id") val requestId: String? = null,
)

@Serializable private data class ResetClaimResultDto(val result: String? = null)

/**
 * Credit amounts arrive as integers, decimals (`4403.0`), or numeric strings depending on the
 * backend variant. Anything non-numeric is treated as absent instead of failing the whole payload.
 */
private object LenientCreditsSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("ClaudeLenientCredits")

    override fun deserialize(decoder: Decoder): Long? {
        val jsonDecoder =
            decoder as? JsonDecoder ?: error("LenientCreditsSerializer requires JsonDecoder")
        val primitive = jsonDecoder.decodeJsonElement() as? JsonPrimitive ?: return null
        return primitive.longOrNull
            ?: primitive.doubleOrNull?.roundToLong()
            ?: primitive.contentOrNull?.toLongOrNull()
            ?: primitive.contentOrNull?.toDoubleOrNull()?.roundToLong()
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        error("Serialization of Claude credit amounts is not supported")
    }
}
