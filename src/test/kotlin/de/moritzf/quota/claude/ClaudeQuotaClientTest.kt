package de.moritzf.quota.claude

import de.moritzf.quota.shared.JsonSupport
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.CompletableFuture
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClaudeQuotaClientTest {
    @Test
    fun parseQuotaExtractsSessionWeeklyAndExtraUsage() {
        val fetchedAt = Instant.parse("2026-06-16T12:00:00Z")
        val quota = ClaudeQuotaClient.parseQuota(USAGE_RESPONSE, fetchedAt)

        assertEquals(fetchedAt, quota.fetchedAt)
        assertEquals(12.5, quota.fiveHourUsage?.usagePercent)
        assertEquals(Instant.parse("2025-12-25T12:00:00Z"), quota.fiveHourUsage?.resetsAt)
        assertEquals(30.0, quota.sevenDayUsage?.usagePercent)
        assertEquals(5.0, quota.sevenDaySonnetUsage?.usagePercent)
        assertEquals(8.0, quota.sevenDayOpusUsage?.usagePercent)
        assertEquals(2.0, quota.sevenDayOauthAppsUsage?.usagePercent)
        assertEquals(18.0, quota.routinesUsage?.usagePercent)
        assertEquals(true, quota.extraUsage?.isEnabled)
        assertEquals(2050L, quota.extraUsage?.monthlyLimitCredits)
        assertEquals(325L, quota.extraUsage?.usedCredits)
        assertEquals(15.85, quota.extraUsage?.usagePercent)
        assertEquals("USD", quota.extraUsage?.currency)
    }

    @Test
    fun parseQuotaExtractsModelScopedLimits() {
        val quota = ClaudeQuotaClient.parseQuota(SCOPED_LIMITS_RESPONSE)

        assertEquals(0.0, quota.fiveHourUsage?.usagePercent)
        assertEquals(17.0, quota.sevenDayUsage?.usagePercent)

        // Unscoped session/weekly_all entries duplicate top-level windows and are skipped.
        assertEquals(1, quota.scopedLimits.size)
        val fable = quota.scopedLimits.single()
        assertEquals("Weekly (Fable)", fable.label)
        assertEquals(34.0, fable.usagePercent)
        assertEquals(Instant.parse("2026-07-26T05:00:00.030850Z"), fable.resetsAt)

        // Scoped limits participate in usage/activity fractions.
        assertEquals(0.34, quota.usageFraction())
        assertEquals(0.51, quota.activityFraction()!!, 0.0001)
    }

    @Test
    fun parseQuotaIgnoresMalformedLimitEntries() {
        val json =
            """
            {
              "five_hour": {"utilization": 5.0, "resets_at": null},
              "limits": [
                {"kind": "weekly_scoped", "group": "weekly", "percent": 12, "scope": {"model": {"id": null, "display_name": ""}, "surface": null}},
                {"kind": "weekly_scoped", "group": "weekly", "scope": {"model": {"display_name": "Fable"}}},
                {"kind": "unknown_kind", "percent": 7, "scope": {"model": {"id": "model-x"}}},
                "not-an-object-is-tolerated-by-kind-nulls"
              ]
            }
            """
                .trimIndent()
                .replace("\"not-an-object-is-tolerated-by-kind-nulls\"", "{}")

        val quota = ClaudeQuotaClient.parseQuota(json)

        // Blank display name, missing percent, and empty entries are dropped; id fallback is kept.
        assertEquals(1, quota.scopedLimits.size)
        assertEquals("Unknown_kind (model-x)", quota.scopedLimits.single().label)
        assertEquals(7.0, quota.scopedLimits.single().usagePercent)
    }

    @Test
    fun parseQuotaAcceptsDecimalCreditsAndSpendBlock() {
        val quota = ClaudeQuotaClient.parseQuota(DECIMAL_CREDITS_RESPONSE)

        assertEquals(0.0, quota.fiveHourUsage?.usagePercent)
        assertEquals(0.0, quota.sevenDayUsage?.usagePercent)
        assertEquals(Instant.parse("2026-07-31T06:59:59.574963Z"), quota.sevenDayUsage?.resetsAt)

        assertEquals(true, quota.extraUsage?.isEnabled)
        assertEquals(10000L, quota.extraUsage?.monthlyLimitCredits)
        assertEquals(4403L, quota.extraUsage?.usedCredits)
        assertEquals(44.03, quota.extraUsage?.usagePercent)
        assertEquals("EUR", quota.extraUsage?.currency)

        // Scoped weekly limit for one model still parses; unscoped entries are skipped.
        assertEquals(1, quota.scopedLimits.size)
        assertEquals("Weekly (Fable)", quota.scopedLimits.single().label)
    }

    @Test
    fun parseQuotaKeepsParsableSectionsWhenOtherBlocksAreMalformed() {
        val json =
            """
            {
              "five_hour": {"utilization": 12.0, "resets_at": null},
              "seven_day": {"utilization": "broken"},
              "extra_usage": ["totally", "reshaped"],
              "limits": [
                {"kind": "weekly_scoped", "group": "weekly", "percent": 34, "scope": {"model": {"display_name": "Fable"}}},
                "garbage-entry",
                {"kind": "weekly_scoped", "group": "weekly", "percent": {"nested": true}, "scope": {"model": {"display_name": "Broken"}}}
              ]
            }
            """
                .trimIndent()

        val quota = ClaudeQuotaClient.parseQuota(json)

        // The parsable window and limit entry survive; malformed blocks are dropped.
        assertEquals(12.0, quota.fiveHourUsage?.usagePercent)
        assertNull(quota.sevenDayUsage)
        assertNull(quota.extraUsage)
        assertEquals(1, quota.scopedLimits.size)
        assertEquals("Weekly (Fable)", quota.scopedLimits.single().label)
        assertEquals(34.0, quota.scopedLimits.single().usagePercent)
    }

    @Test
    fun parseQuotaReportsChangedPayload() {
        val exception =
            assertFailsWith<ClaudeQuotaException> {
                ClaudeQuotaClient.parseQuota("""{"ok":true}""")
            }
        assertEquals("Claude usage response changed.", exception.message)
    }

    @Test
    fun fetchQuotaUsesOAuthUsageEndpointAndHeaders() {
        val httpClient = FakeHttpClient(FakeResponseSpec(USAGE_RESPONSE))
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val quota = client.fetchQuota("token-123")

        assertEquals(12.5, quota.fiveHourUsage?.usagePercent)
        assertEquals(
            listOf("https://api.anthropic.test/api/oauth/usage?cedar_ember=1"),
            httpClient.requests.map { it.uri().toString() },
        )
        val request = httpClient.requests.single()
        assertEquals("Bearer token-123", request.headers().firstValue("Authorization").orElse(null))
        assertEquals(
            "oauth-2025-04-20",
            request.headers().firstValue("anthropic-beta").orElse(null),
        )
        assertEquals(
            "claude-cli/2.1.87 (external, cli)",
            request.headers().firstValue("User-Agent").orElse(null),
        )
        assertTrue(quota.rawJson?.contains("five_hour") == true)
    }

    @Test
    fun fetchQuotaRejectsMissingTokenBeforeNetworkCall() {
        val httpClient = FakeHttpClient(FakeResponseSpec(USAGE_RESPONSE))
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val exception = assertFailsWith<ClaudeQuotaException> { client.fetchQuota(" ") }

        assertEquals("Claude login required. Log in from Claude settings.", exception.message)
        assertEquals(emptyList(), httpClient.requests)
    }

    @Test
    fun fetchQuotaTreatsAuthRejectionAsExpiredLogin() {
        val client =
            ClaudeQuotaClient(
                FakeHttpClient(FakeResponseSpec("""{"error":"unauthorized"}""", status = 401)),
                URI.create("https://api.anthropic.test/api/oauth/usage"),
            )

        val exception = assertFailsWith<ClaudeQuotaException> { client.fetchQuota("token-123") }

        assertEquals("Claude usage API rejected the access token (HTTP 401).", exception.message)
        assertEquals(401, exception.statusCode)
    }

    @Test
    fun parseResetsReadsOffersAndTheSessionReset() {
        val resets =
            ClaudeQuotaClient.parseResets(
                """
                {
                  "cedar_ember": {
                    "eligible": true,
                    "next_grant_id": "weekly_gift",
                    "grants": [
                      { "id": "session_gift", "label": "Session reset", "resets_left": 1,
                        "ends_at": "2026-11-01T00:00:00+00:00", "usable_now": false,
                        "clears": ["five_hour"] },
                      { "id": "weekly_gift", "label": "Weekly reset", "resets_left": 2,
                        "usable_now": true, "clears": ["seven_day", "seven_day_opus"],
                        "future_field": {} },
                      { "id": "paused_gift", "resets_left": 1, "paused": true, "usable_now": true },
                      { "id": "Not A Valid Id", "resets_left": 1, "usable_now": true },
                      { "id": "broken", "resets_left": "many" }
                    ]
                  },
                  "juniper_tide": {
                    "eligible": true, "arm": "reset", "available": true,
                    "weekly_resets_at": "2026-10-26T07:00:00+00:00"
                  }
                }
                """
            )!!

        assertEquals(
            listOf("session_gift", "weekly_gift", "paused_gift"),
            resets.grants.map { it.id },
        )
        assertEquals("weekly_gift", resets.nextGrantId)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), resets.grants.first().endsAt)
        assertEquals(listOf("seven_day", "seven_day_opus"), resets.grants[1].clears)
        assertEquals(1 + 2 + 1, resets.availableCount())
        assertEquals(
            listOf(
                ClaudeResetChoice.Session(
                    ClaudeSessionReset(Instant.parse("2026-10-26T07:00:00Z"))
                ),
                ClaudeResetChoice.Grant(resets.grants[1]),
            ),
            resets.choices(),
        )
    }

    @Test
    fun parseResetsIgnoresControlArmsAndIneligibleOffers() {
        assertNull(
            ClaudeQuotaClient.parseResets(
                """
                {
                  "cedar_ember": { "eligible": false, "grants": [
                    { "id": "gift", "resets_left": 1, "usable_now": true } ] },
                  "juniper_tide": { "eligible": true, "arm": "control", "available": true }
                }
                """
            )
        )
        assertNull(ClaudeQuotaClient.parseResets("""{"cedar_ember": null, "juniper_tide": null}"""))
        assertNull(ClaudeQuotaClient.parseResets("not json"))
    }

    @Test
    fun fetchQuotaReadsUsageAndResetsInOneRequest() {
        val body =
            USAGE_RESPONSE.replaceFirst(
                "{",
                """{"cedar_ember": {"eligible": true, "grants": [
                    {"id": "weekly_gift", "resets_left": 1, "usable_now": true,
                     "clears": ["seven_day"]}]},""",
            )
        val httpClient = FakeHttpClient(FakeResponseSpec(body))
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val quota = client.fetchQuota("token-123")

        assertEquals(1, httpClient.requests.size)
        assertEquals(listOf("weekly_gift"), quota.resets?.grants?.map { it.id })
        assertEquals(12.5, quota.fiveHourUsage?.usagePercent)
        assertEquals(body, quota.rawJson)
    }

    @Test
    fun fetchQuotaFallsBackToThePlainReadWhenTheResetFlagIsRejected() {
        val httpClient =
            FakeHttpClient(
                FakeResponseSpec("""{"error":"bad flag"}""", status = 400),
                FakeResponseSpec(USAGE_RESPONSE),
            )
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val quota = client.fetchQuota("token-123")

        assertEquals(12.5, quota.fiveHourUsage?.usagePercent)
        assertNull(quota.resets)
        assertEquals(
            listOf(
                "https://api.anthropic.test/api/oauth/usage?cedar_ember=1",
                "https://api.anthropic.test/api/oauth/usage",
            ),
            httpClient.requests.map { it.uri().toString() },
        )
    }

    @Test
    fun rateLimitCarriesTheRequestedPause() {
        val seconds =
            ClaudeQuotaClient(
                FakeHttpClient(
                    FakeResponseSpec(
                        """{"error":{"type":"rate_limit_error"}}""",
                        status = 429,
                        headers = mapOf("Retry-After" to listOf("120")),
                    )
                ),
                URI.create("https://api.anthropic.test/api/oauth/usage"),
            )
        val noHeader =
            ClaudeQuotaClient(
                FakeHttpClient(FakeResponseSpec("{}", status = 429)),
                URI.create("https://api.anthropic.test/api/oauth/usage"),
            )

        val limited = assertFailsWith<ClaudeQuotaException> { seconds.fetchQuota("token") }
        val unlimited = assertFailsWith<ClaudeQuotaException> { noHeader.fetchQuota("token") }

        assertEquals(429, limited.statusCode)
        assertEquals(java.time.Duration.ofSeconds(120), limited.retryAfter)
        assertEquals("""{"error":{"type":"rate_limit_error"}}""", limited.rawBody)
        assertNull(unlimited.retryAfter)
    }

    @Test
    fun retryAfterAcceptsAnHttpDate() {
        val now = java.time.Instant.parse("2026-10-22T10:00:00Z")
        fun retryAfter(value: String): java.time.Duration? {
            val request =
                HttpRequest.newBuilder(URI.create("https://api.anthropic.test/api/oauth/usage"))
                    .build()
            return ClaudeQuotaClient.retryAfter(
                FakeResponse("", 429, request, mapOf("Retry-After" to listOf(value))),
                now,
            )
        }

        assertEquals(java.time.Duration.ofMinutes(5), retryAfter("Thu, 22 Oct 2026 10:05:00 GMT"))
        assertNull(retryAfter("Thu, 22 Oct 2026 09:55:00 GMT"))
        assertNull(retryAfter("soon"))
        assertNull(retryAfter("-5"))
    }

    @Test
    fun fetchAtLimitResetsUsesTheAtTheWallRead() {
        val httpClient =
            FakeHttpClient(
                FakeResponseSpec(
                    """{"juniper_tide": {"arm": "reset", "available": true,
                        "weekly_resets_at": "2026-10-26T07:00:00+00:00"}}"""
                )
            )
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        val resets = client.fetchAtLimitResets("token-123")

        assertEquals(
            listOf("https://api.anthropic.test/api/oauth/usage?at_wall=1&skip_spend=1"),
            httpClient.requests.map { it.uri().toString() },
        )
        assertEquals(Instant.parse("2026-10-26T07:00:00Z"), resets?.sessionReset?.weeklyResetsAt)
        assertEquals(
            "Bearer token-123",
            httpClient.requests.first().headers().firstValue("Authorization").orElse(null),
        )
    }

    @Test
    fun mergedResetsKeepRegularGrantsAndTheAtLimitSessionReset() {
        val grant = ClaudeResetGrant("gift", "", 1, null, false, true, emptyList())
        val session = ClaudeSessionReset(null)

        assertEquals(
            ClaudeResets(listOf(grant), "gift", session),
            ClaudeResets.merge(
                ClaudeResets(listOf(grant), "gift"),
                ClaudeResets(sessionReset = session),
            ),
        )
        assertEquals(
            ClaudeResets(listOf(grant), null, session),
            ClaudeResets.merge(null, ClaudeResets(listOf(grant), sessionReset = session)),
        )
        assertNull(ClaudeResets.merge(null, null))
        assertNull(ClaudeResets.merge(ClaudeResets(), ClaudeResets()))
    }

    @Test
    fun parseQuotaKeepsSurfaceScopedLimits() {
        val quota =
            ClaudeQuotaClient.parseQuota(
                """
                {
                  "five_hour": {"utilization": 5.0, "resets_at": null},
                  "limits": [
                    {"kind": "weekly_scoped", "group": "weekly", "percent": 41,
                     "resets_at": "2026-10-29T07:00:00+00:00",
                     "scope": {"model": null, "surface": {"id": "cowork", "display_name": "Cowork"}}},
                    {"kind": "weekly_scoped", "group": "weekly", "percent": 12,
                     "scope": {"model": null, "surface": {"id": "claude_code"}}},
                    {"kind": "weekly_scoped", "group": "weekly", "percent": 3,
                     "scope": {"surface": "mobile"}}
                  ]
                }
                """
            )

        assertEquals(
            listOf(
                "Weekly (Cowork)" to 41.0,
                "Weekly (claude_code)" to 12.0,
                "Weekly (mobile)" to 3.0,
            ),
            quota.scopedLimits.map { it.label to it.usagePercent },
        )
        assertEquals(Instant.parse("2026-10-29T07:00:00Z"), quota.scopedLimits.first().resetsAt)
    }

    @Test
    fun parseQuotaReadsCreditPoolsOutsideThePlanLimits() {
        val quota =
            ClaudeQuotaClient.parseQuota(
                """
                {
                  "five_hour": {"utilization": 90, "resets_at": null},
                  "cinder_cove": {"utilization": 25, "resets_at": "2026-11-30T00:00:00Z"},
                  "wattle_ember": {"label": "Fable credit", "utilization": null,
                    "limit_dollars": 50, "used_dollars": 12.5, "remaining_dollars": 37.5,
                    "resets_at": "2026-11-05T00:00:00Z",
                    "periods": [
                      {"starts_at": "2026-10-22T00:00:00Z", "ends_at": "2026-11-05T00:00:00Z"},
                      {"starts_at": "2026-11-05T00:00:00Z", "ends_at": "2026-11-19T00:00:00Z"}
                    ]},
                  "iguana_necktie": {"limit_dollars": 20, "remaining_dollars": 15,
                    "resets_at": "2026-12-01T00:00:00Z", "locked_reason": null},
                  "unknown_pool": {"utilization": 99}
                }
                """
            )

        assertEquals(
            listOf(
                ClaudeCredit(
                    "Claude Code and Cowork credit",
                    25.0,
                    endsAt = Instant.parse("2026-11-30T00:00:00Z"),
                ),
                ClaudeCredit(
                    "Fable credit",
                    25.0,
                    12.5,
                    50.0,
                    Instant.parse("2026-11-05T00:00:00Z"),
                    renews = true,
                ),
                ClaudeCredit(
                    "Cloud credits",
                    25.0,
                    5.0,
                    20.0,
                    Instant.parse("2026-12-01T00:00:00Z"),
                ),
            ),
            quota.credits,
        )
        // Credits are not plan limits: the status bar keeps showing the 5-hour window.
        assertEquals(0.9, quota.usageFraction())
        assertTrue(!quota.atLimit())
    }

    @Test
    fun parseQuotaAcceptsAResponseWithOnlyACreditPool() {
        val quota =
            ClaudeQuotaClient.parseQuota(
                """{"cinder_cove": {"utilization": 0, "resets_at": null}}"""
            )

        assertEquals(listOf("Claude Code and Cowork credit"), quota.credits.map { it.label })
        assertTrue(quota.hasUsageState())
    }

    @Test
    fun parseQuotaReadsUnlimitedAndBlockedUsageCredits() {
        val unlimited =
            ClaudeQuotaClient.parseQuota(
                """
                {"five_hour": {"utilization": 1}, "extra_usage": {"is_enabled": true,
                  "monthly_limit": null, "used_credits": 1250, "utilization": null,
                  "currency": "USD"}}
                """
            )
        val capped =
            ClaudeQuotaClient.parseQuota(
                """
                {"five_hour": {"utilization": 1}, "extra_usage": {"is_enabled": true,
                  "used_credits": 1250, "currency": "USD"}}
                """
            )
        val blocked =
            ClaudeQuotaClient.parseQuota(
                """
                {"five_hour": {"utilization": 1}, "extra_usage": {"is_enabled": false,
                  "disabled_reason": "out_of_credits"}}
                """
            )

        assertEquals(true, unlimited.extraUsage?.unlimited)
        assertEquals(12.5, unlimited.extraUsage?.usedMajor)
        assertNull(unlimited.extraUsage?.usagePercent)
        assertEquals(false, capped.extraUsage?.unlimited, "an absent cap is not an unlimited one")
        assertEquals(false, blocked.extraUsage?.isEnabled)
        assertEquals("out_of_credits", blocked.extraUsage?.disabledReason)
    }

    @Test
    fun parseQuotaReadsThePrepaidBalanceFromTheSpendBlock() {
        val quota =
            ClaudeQuotaClient.parseQuota(
                """
                {"five_hour": {"utilization": 1},
                 "spend": {"balance": {"amount_minor": 1234, "currency": "EUR", "exponent": 2},
                           "auto_reload": null}}
                """
            )
        val withoutBalance = ClaudeQuotaClient.parseQuota(DECIMAL_CREDITS_RESPONSE)

        assertEquals(ClaudeMoney(1234, "EUR", 2), quota.creditBalance)
        assertEquals(12.34, quota.creditBalance?.major)
        assertNull(withoutBalance.creditBalance)
    }

    @Test
    fun fetchProfileReadsTheOrganizationAndPlan() {
        val httpClient =
            FakeHttpClient(
                FakeResponseSpec(
                    """{"account": {"uuid": "a"}, "organization": {"uuid": "$ORGANIZATION",
                       "organization_type": "claude_max", "rate_limit_tier": "default_claude_max_20x",
                       "plan_display_name": null}}"""
                )
            )
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        assertEquals(ClaudeProfile(ORGANIZATION, "Max 20x"), client.fetchProfile("token-123"))
        assertEquals(
            "https://api.anthropic.test/api/oauth/profile",
            httpClient.requests.single().uri().toString(),
        )
    }

    @Test
    fun planLabelPrefersTheProfileDisplayName() {
        assertEquals("Max 5x", ClaudeQuotaClient.planLabel("Max 5x", "claude_max", null))
        assertEquals(
            "Max 20x",
            ClaudeQuotaClient.planLabel("<b>spoof</b>", "claude_max", "default_claude_max_20x"),
        )
        assertEquals("Max", ClaudeQuotaClient.planLabel(null, "claude_max", "default_claude_ai"))
        assertEquals("Pro", ClaudeQuotaClient.planLabel("", "claude_pro", null))
        assertEquals("Team", ClaudeQuotaClient.planLabel(null, "claude_team", null))
        assertEquals("Enterprise", ClaudeQuotaClient.planLabel(null, "claude_enterprise", null))
        assertEquals("", ClaudeQuotaClient.planLabel(null, "claude_free", null))
        assertEquals("", ClaudeQuotaClient.planLabel(" ", null, null))
    }

    @Test
    fun redeemResetPostsTheProgramAndGrantToTheOrganization() {
        val httpClient =
            FakeHttpClient(
                FakeResponseSpec("""{"organization":{"uuid":"$ORGANIZATION"}}"""),
                FakeResponseSpec("""{"result":"reset","resets_left":1}"""),
                FakeResponseSpec("""{"result":"not_limited"}"""),
                FakeResponseSpec("""{"error":"slow down"}""", status = 429),
            )
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))
        val grant =
            ClaudeResetGrant(
                "weekly_gift",
                "Weekly reset",
                1,
                null,
                false,
                true,
                listOf("seven_day"),
            )

        val organization = client.fetchOrganizationUuid("token-123")
        val granted = client.redeemReset("token-123", organization, ClaudeResetChoice.Grant(grant))
        val session =
            client.redeemReset(
                "token-123",
                organization,
                ClaudeResetChoice.Session(ClaudeSessionReset(null)),
            )
        val busy =
            client.redeemReset(
                "token-123",
                organization,
                ClaudeResetChoice.Session(ClaudeSessionReset(null)),
            )

        assertEquals(ORGANIZATION, organization)
        assertTrue(granted.succeeded)
        assertEquals("not_limited", session.result)
        assertEquals(ClaudeResetOutcome.RATE_LIMITED, busy.result)
        assertEquals(
            listOf("GET", "POST", "POST", "POST"),
            httpClient.requests.map { it.method() },
        )
        assertEquals(
            "https://api.anthropic.test/api/oauth/profile",
            httpClient.requests.first().uri().toString(),
        )
        assertEquals(
            "https://api.anthropic.test/api/organizations/$ORGANIZATION/reset_rate_limits",
            httpClient.requests[1].uri().toString(),
        )
        val grantClaim = JsonSupport.json.parseToJsonElement(httpClient.requests[1].bodyText())
        assertEquals("cedar_ember", grantClaim.jsonObject["program"]?.jsonPrimitive?.content)
        assertEquals("weekly_gift", grantClaim.jsonObject["grant_id"]?.jsonPrimitive?.content)
        assertTrue(
            grantClaim.jsonObject["request_id"]?.jsonPrimitive?.content?.isNotBlank() == true
        )
        assertEquals(
            mapOf("program" to "juniper_tide"),
            JsonSupport.json
                .parseToJsonElement(httpClient.requests[2].bodyText())
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content },
        )
    }

    @Test
    fun redeemResetRefusesAnOrganizationIdThatIsNotAUuid() {
        val httpClient = FakeHttpClient()
        val client =
            ClaudeQuotaClient(httpClient, URI.create("https://api.anthropic.test/api/oauth/usage"))

        assertFailsWith<IllegalArgumentException> {
            client.redeemReset(
                "token-123",
                "../admin",
                ClaudeResetChoice.Session(ClaudeSessionReset(null)),
            )
        }
        assertEquals(emptyList(), httpClient.requests)
    }

    private fun HttpRequest.bodyText(): String {
        val bytes = java.io.ByteArrayOutputStream()
        val done = java.util.concurrent.CountDownLatch(1)
        bodyPublisher()
            .orElseThrow()
            .subscribe(
                object : java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
                    override fun onSubscribe(subscription: java.util.concurrent.Flow.Subscription) =
                        subscription.request(Long.MAX_VALUE)

                    override fun onNext(item: java.nio.ByteBuffer) {
                        val chunk = ByteArray(item.remaining())
                        item.get(chunk)
                        bytes.write(chunk)
                    }

                    override fun onError(throwable: Throwable) = done.countDown()

                    override fun onComplete() = done.countDown()
                }
            )
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
        return bytes.toString(Charsets.UTF_8)
    }

    private data class FakeResponseSpec(
        val body: String,
        val status: Int = 200,
        val headers: Map<String, List<String>> = emptyMap(),
    )

    private class FakeHttpClient(private vararg val responses: FakeResponseSpec) : HttpClient() {
        val requests = mutableListOf<HttpRequest>()
        private var index = 0

        override fun <T : Any?> send(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): HttpResponse<T> {
            requests.add(request)
            val response = responses[index++]
            @Suppress("UNCHECKED_CAST")
            return FakeResponse(response.body, response.status, request, response.headers)
                as HttpResponse<T>
        }

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

        override fun <T : Any?> sendAsync(
            request: HttpRequest,
            responseBodyHandler: HttpResponse.BodyHandler<T>,
            pushPromiseHandler: HttpResponse.PushPromiseHandler<T>,
        ): CompletableFuture<HttpResponse<T>> = throw UnsupportedOperationException()

        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()

        override fun connectTimeout(): Optional<java.time.Duration> = Optional.empty()

        override fun followRedirects(): Redirect = Redirect.NEVER

        override fun proxy(): Optional<ProxySelector> = Optional.empty()

        override fun sslContext(): SSLContext = SSLContext.getDefault()

        override fun sslParameters(): SSLParameters = SSLParameters()

        override fun authenticator(): Optional<Authenticator> = Optional.empty()

        override fun version(): Version = Version.HTTP_1_1

        override fun executor(): Optional<java.util.concurrent.Executor> = Optional.empty()
    }

    private class FakeResponse(
        private val body: String,
        private val status: Int,
        private val request: HttpRequest,
        private val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpResponse<String> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest = request

        override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()

        override fun headers(): HttpHeaders = HttpHeaders.of(headers) { _, _ -> true }

        override fun body(): String = body

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri(): URI = request.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    companion object {
        private const val ORGANIZATION = "6f7d2a10-3c4b-4d5e-8f90-a1b2c3d4e5f6"
        private val USAGE_RESPONSE =
            """
            {
              "five_hour": { "utilization": 12.5, "resets_at": "2025-12-25T12:00:00.000Z" },
              "seven_day": { "utilization": 30, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_sonnet": { "utilization": 5, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_opus": { "utilization": 8, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_oauth_apps": { "utilization": 2, "resets_at": "2025-12-31T00:00:00.000Z" },
              "seven_day_routines": { "utilization": 18, "resets_at": "2025-12-31T00:00:00.000Z" },
              "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 2050,
                "used_credits": 325,
                "utilization": 15.85,
                "currency": "USD"
              }
            }
            """
                .trimIndent()

        /** Real-world payload shape with a model-scoped weekly limit in the `limits` array. */
        private val SCOPED_LIMITS_RESPONSE =
            """
            {
              "five_hour": {"utilization": 0.0, "resets_at": null, "limit_dollars": null, "used_dollars": null, "remaining_dollars": null},
              "seven_day": {"utilization": 17.0, "resets_at": "2026-07-26T05:00:00.030579+00:00", "limit_dollars": null, "used_dollars": null, "remaining_dollars": null},
              "seven_day_oauth_apps": null,
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "extra_usage": {"is_enabled": false, "monthly_limit": null, "used_credits": null, "utilization": null, "currency": null},
              "limits": [
                {"kind": "session", "group": "session", "percent": 0, "severity": "normal", "resets_at": null, "scope": null, "is_active": false},
                {"kind": "weekly_all", "group": "weekly", "percent": 17, "severity": "normal", "resets_at": "2026-07-26T05:00:00.030579+00:00", "scope": null, "is_active": false},
                {"kind": "weekly_scoped", "group": "weekly", "percent": 34, "severity": "normal", "resets_at": "2026-07-26T05:00:00.030850+00:00", "scope": {"model": {"id": null, "display_name": "Fable"}, "surface": null}, "is_active": true}
              ],
              "member_dashboard_available": false
            }
            """
                .trimIndent()

        /**
         * Real-world payload with decimal `used_credits`, a `spend` block, and null feature
         * windows.
         */
        private val DECIMAL_CREDITS_RESPONSE =
            """
            {
              "five_hour": {
                "utilization": 0.0,
                "resets_at": null,
                "limit_dollars": null,
                "used_dollars": null,
                "remaining_dollars": null
              },
              "seven_day": {
                "utilization": 0.0,
                "resets_at": "2026-07-31T06:59:59.574963+00:00",
                "limit_dollars": null,
                "used_dollars": null,
                "remaining_dollars": null
              },
              "seven_day_oauth_apps": null,
              "seven_day_opus": null,
              "seven_day_sonnet": null,
              "seven_day_cowork": null,
              "seven_day_omelette": null,
              "tangelo": null,
              "iguana_necktie": null,
              "omelette_promotional": null,
              "nimbus_quill": null,
              "cinder_cove": null,
              "amber_ladder": null,
              "extra_usage": {
                "is_enabled": true,
                "monthly_limit": 10000,
                "used_credits": 4403.0,
                "utilization": 44.03,
                "currency": "EUR",
                "decimal_places": 2,
                "disabled_reason": null,
                "user_disabled": false,
                "spend_limit_reached": false,
                "credits_ever_enabled": true,
                "daily": null,
                "weekly": null
              },
              "limits": [
                {
                  "kind": "session",
                  "group": "session",
                  "percent": 0,
                  "severity": "normal",
                  "resets_at": null,
                  "scope": null,
                  "is_active": true
                },
                {
                  "kind": "weekly_all",
                  "group": "weekly",
                  "percent": 0,
                  "severity": "normal",
                  "resets_at": "2026-07-31T06:59:59.574963+00:00",
                  "scope": null,
                  "is_active": false
                },
                {
                  "kind": "weekly_scoped",
                  "group": "weekly",
                  "percent": 0,
                  "severity": "normal",
                  "resets_at": null,
                  "scope": {
                    "model": {
                      "id": null,
                      "display_name": "Fable"
                    },
                    "surface": null
                  },
                  "is_active": false
                }
              ],
              "spend": {
                "used": {"amount_minor": 4403, "currency": "EUR", "exponent": 2},
                "limit": {"amount_minor": 10000, "currency": "EUR", "exponent": 2},
                "percent": 44,
                "severity": "normal",
                "enabled": true,
                "disabled_reason": null,
                "cap": {"money": {"amount_minor": 10000, "currency": "EUR", "exponent": 2}, "credits": null},
                "balance": null,
                "auto_reload": null,
                "disclaimer": "Usage credits cover you when you hit your plan limits. [Learn more](https://support.claude.com/articles/12429409)",
                "can_purchase_credits": false,
                "can_toggle": false
              },
              "member_dashboard_available": false
            }
            """
                .trimIndent()
    }
}
