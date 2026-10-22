package de.moritzf.quota.idea.common

import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.claude.ClaudeQuotaClient
import de.moritzf.quota.claude.ClaudeQuotaException
import de.moritzf.quota.claude.ClaudeResetChoice
import de.moritzf.quota.claude.ClaudeResetGrant
import de.moritzf.quota.claude.ClaudeResetOutcome
import de.moritzf.quota.claude.ClaudeResets
import de.moritzf.quota.claude.ClaudeSessionReset
import de.moritzf.quota.claude.ClaudeUsageWindow
import de.moritzf.quota.idea.auth.OAuthConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ClaudeQuotaProviderTest {
    @Test
    fun refreshStoresQuotaOnSuccess() {
        val quota =
            ClaudeQuota(fiveHourUsage = null, sevenDayUsage = null, rawJson = "{\"ok\":true}")
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { quota },
                tokenProvider = { "token" },
                tokenRefresher = { null },
            )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertNull(provider.getLastError())
    }

    @Test
    fun refreshClearsDataWhenNotLoggedIn() {
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { throw ClaudeQuotaException("unused") },
                tokenProvider = { null },
                tokenRefresher = { null },
                connectionStateProvider = { OAuthConnectionState.LOGGED_OUT },
            )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals(provider.notConfiguredMessage, provider.getLastError())
    }

    @Test
    fun refreshKeepsLoginAndLastQuotaWhenTokenIsTemporarilyUnavailable() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var token: String? = "token"
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { quota },
                tokenProvider = { token },
                tokenRefresher = { null },
                connectionStateProvider = { OAuthConnectionState.TEMPORARY_FAILURE },
            )

        provider.refresh()
        token = null
        provider.refresh()

        // A failed refresh must not look like a logout, and must not drop the last reading.
        assertSame(quota, provider.getLastQuota())
        assertNotEquals(provider.notConfiguredMessage, provider.getLastError())
        assertTrue(provider.isLastErrorTransient())
        assertEquals(
            "Claude token could not be refreshed. Trying again with the next update.",
            provider.getLastError(),
        )
    }

    @Test
    fun refreshKeepsLastQuotaOnRateLimitWhenPreviousDataExists() {
        val firstQuota = ClaudeQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetchCount++
                        if (fetchCount == 1) firstQuota
                        else
                            throw ClaudeQuotaException(
                                "Claude usage API rate limited. Try again later.",
                                429,
                            )
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
            )

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertNull(provider.getLastError())

        provider.refresh()
        assertSame(firstQuota, provider.getLastQuota())
        assertTrue(
            provider.isLastErrorTransient(),
            "a rate limit must not replace the last quota on screen",
        )
    }

    @Test
    fun rateLimitKeepsTheLastResponseThatWasNotRateLimited() {
        val responses =
            ArrayDeque<() -> ClaudeQuota>(
                listOf(
                    { ClaudeQuota(rawJson = """{"first":true}""") },
                    { throw ClaudeQuotaException("rate limited", 429, """{"limit":1}""") },
                    { throw ClaudeQuotaException("rate limited", 429, null) },
                    { throw ClaudeQuotaException("server error", 500, """{"server":1}""") },
                    { throw ClaudeQuotaException("rate limited", 429, """{"limit":2}""") },
                    { ClaudeQuota(rawJson = """{"second":true}""") },
                )
            )
        var now = START
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { responses.removeFirst()() },
                tokenProvider = { "token" },
                tokenRefresher = { null },
                clock = { now },
            )

        fun refresh(): Pair<String?, String?> {
            provider.refresh()
            now += 2.hours
            return provider.getLastRawJson() to provider.getRawJsonBeforeRateLimit()
        }

        assertEquals("""{"first":true}""" to null, refresh())
        assertEquals("""{"limit":1}""" to """{"first":true}""", refresh())
        assertEquals("""{"limit":1}""" to """{"first":true}""", refresh())
        assertEquals("""{"server":1}""" to null, refresh())
        assertEquals("""{"limit":2}""" to """{"server":1}""", refresh())
        assertEquals("""{"second":true}""" to null, refresh())
    }

    @Test
    fun refreshSurfacesErrorWhenRateLimitHasNoPreviousData() {
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        throw ClaudeQuotaException("Claude usage API rate limited.", 429)
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
            )

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertTrue(
            provider
                .getLastError()!!
                .matches(Regex("""Claude usage API rate limited\. Next try at \d\d:\d\d\.""")),
            provider.getLastError(),
        )
    }

    @Test
    fun rateLimitPausesUsageReadsWithGrowingBackoff() {
        var now = START
        val fetches = mutableListOf<Instant>()
        var limited = true
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetches += now
                        if (limited) throw ClaudeQuotaException("rate limited", 429)
                        ClaudeQuota(rawJson = "{}")
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
                clock = { now },
            )

        // Every minute for two hours: 5, 10, 20, 40, then the one-hour cap between reads.
        repeat(120) {
            provider.refresh()
            now += 1.minutes
        }

        assertEquals(
            listOf(0, 5, 15, 35, 75).map { START + it.minutes },
            fetches,
        )
        limited = false
        now = fetches.last() + 1.hours
        provider.refresh()
        assertNull(provider.getLastError())
        // A successful read ends the backoff: the next rate limit pauses for 5 minutes again.
        limited = true
        provider.refresh()
        val afterSuccess = now
        now += 4.minutes
        provider.refresh()
        now += 1.minutes
        provider.refresh()
        assertEquals(
            listOf(afterSuccess, afterSuccess, afterSuccess + 5.minutes),
            fetches.takeLast(3),
        )
    }

    @Test
    fun rateLimitHonorsRetryAfterUpToAnHour() {
        var now = START
        var retryAfter = java.time.Duration.ofSeconds(90)
        var fetches = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetches++
                        throw ClaudeQuotaException("rate limited", 429, retryAfter = retryAfter)
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
                clock = { now },
            )

        provider.refresh()
        now += 89.seconds
        provider.refresh()
        assertEquals(1, fetches)
        now += 1.seconds
        provider.refresh()
        assertEquals(2, fetches)

        retryAfter = java.time.Duration.ofHours(3)
        now += 2.minutes
        provider.refresh()
        now += 59.minutes
        provider.refresh()
        assertEquals(3, fetches)
        now += 1.minutes
        provider.refresh()
        assertEquals(4, fetches)
    }

    @Test
    fun rateLimitPauseDoesNotApplyToAnotherLogin() {
        var token = "first"
        var fetches = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetches++
                        throw ClaudeQuotaException("rate limited", 429)
                    },
                tokenProvider = { token },
                tokenRefresher = { null },
                clock = { START },
            )

        provider.refresh()
        provider.refresh()
        token = "second"
        provider.refresh()

        assertEquals(2, fetches)
    }

    @Test
    fun otherFailuresDoNotPauseUsageReads() {
        var fetches = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetches++
                        throw ClaudeQuotaException("server error", 500)
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
                clock = { START },
            )

        repeat(3) { provider.refresh() }

        assertEquals(3, fetches)
    }

    @Test
    fun refreshSurfacesNonRateLimitErrorEvenWithPreviousData() {
        val firstQuota = ClaudeQuota(rawJson = "{\"first\":true}")
        var fetchCount = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetchCount++
                        if (fetchCount == 1) firstQuota
                        else
                            throw ClaudeQuotaException(
                                "Claude usage request failed (HTTP 500).",
                                500,
                            )
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
            )

        provider.refresh()
        provider.refresh()

        assertSame(firstQuota, provider.getLastQuota())
        assertEquals("Claude usage request failed (HTTP 500).", provider.getLastError())
    }

    @Test
    fun refreshRetriesOnceAfterUnauthorizedResponse() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var fetchCount = 0
        var refreshCount = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetchCount++
                        if (fetchCount == 1) throw ClaudeQuotaException("expired", 401)
                        quota
                    },
                tokenProvider = { "old-token" },
                tokenRefresher = {
                    refreshCount++
                    "new-token"
                },
            )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertEquals(1, refreshCount)
        assertEquals(2, fetchCount)
    }

    @Test
    fun refreshRetriesOnceAfterGenericForbiddenResponse() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var fetchCount = 0
        var refreshCount = 0
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        fetchCount++
                        if (fetchCount == 1) throw ClaudeQuotaException("expired", 403, "forbidden")
                        quota
                    },
                tokenProvider = { "old-token" },
                tokenRefresher = {
                    refreshCount++
                    "new-token"
                },
            )

        provider.refresh()

        assertSame(quota, provider.getLastQuota())
        assertEquals(1, refreshCount)
        assertEquals(2, fetchCount)
    }

    @Test
    fun refreshDoesNotRotateTokenAfterForbiddenResponse() {
        var refreshCount = 0
        var rejectedToken: String? = null
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        throw ClaudeQuotaException(
                            "Claude token is missing the user:profile scope required for usage.",
                            403,
                            "missing user:profile",
                        )
                    },
                tokenProvider = { "token" },
                tokenRefresher = {
                    refreshCount++
                    "new-token"
                },
                reconnectRequired = { rejectedToken = it },
            )

        provider.refresh()

        assertEquals(0, refreshCount)
        assertEquals("token", rejectedToken)
        assertEquals(
            "Claude token is missing the user:profile scope required for usage.",
            provider.getLastError(),
        )
    }

    @Test
    fun rejectedRefreshKeepsQuotaButRequiresReconnect() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var token: String? = "token"
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { quota },
                tokenProvider = { token },
                connectionStateProvider = { OAuthConnectionState.RECONNECT_REQUIRED },
            )
        provider.refresh()
        token = null
        provider.refresh()
        assertSame(quota, provider.getLastQuota())
        assertFalse(provider.isLastErrorTransient())
        assertTrue(provider.getLastError()!!.contains("Log in again"))
    }

    @Test
    fun temporaryRefreshFailureAfterUnauthorizedDoesNotReportLogout() {
        val quota = ClaudeQuota(rawJson = "{\"ok\":true}")
        var fail = false
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient {
                        if (fail) throw ClaudeQuotaException("rejected", 401)
                        quota
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
                connectionStateProvider = { OAuthConnectionState.TEMPORARY_FAILURE },
            )
        provider.refresh()
        fail = true
        provider.refresh()
        assertSame(quota, provider.getLastQuota())
        assertTrue(provider.isLastErrorTransient())
        assertTrue(provider.getLastError()!!.contains("Trying again"))
    }

    @Test
    fun freshTokenRejectedByUsageRequiresReconnect() {
        var rejectedToken: String? = null
        var refreshCount = 0
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { throw ClaudeQuotaException("rejected", 401) },
                tokenProvider = { "old" },
                tokenRefresher = {
                    refreshCount++
                    "fresh"
                },
                reconnectRequired = { rejectedToken = it },
            )
        provider.refresh()
        assertEquals("fresh", rejectedToken)
        assertEquals(1, refreshCount)
        assertFalse(provider.isLastErrorTransient())
    }

    @Test
    fun genericForbiddenResponseDoesNotRequireNewLogin() {
        val provider =
            ClaudeQuotaProvider(
                client = FakeClaudeClient { throw ClaudeQuotaException("permission denied", 403) },
                tokenProvider = { "old" },
                tokenRefresher = { "fresh" },
                reconnectRequired = {
                    error("a permission failure does not prove that login expired")
                },
            )
        provider.refresh()
        assertEquals("permission denied", provider.getLastError())
    }

    @Test
    fun refreshReadsTheSessionResetOnlyAtTheLimitAndKeepsItWhenTheReadFails() {
        var now = START
        var atLimitReads = 0
        var percent = 100.0
        val session = ClaudeSessionReset(null)
        val grant = ClaudeResetGrant("gift", "", 1, null, false, true, listOf("seven_day"))
        var failResets = false
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient(
                        fetchAtLimitResets = {
                            atLimitReads++
                            if (failResets) throw ClaudeQuotaException("rate limited", 429)
                            ClaudeResets(sessionReset = session)
                        }
                    ) {
                        ClaudeQuota(
                            fiveHourUsage = ClaudeUsageWindow(usagePercent = percent),
                            resets = ClaudeResets(listOf(grant)),
                        )
                    },
                tokenProvider = { "token" },
                tokenRefresher = { null },
                clock = { now },
            )

        provider.refresh()
        assertEquals(ClaudeResets(listOf(grant), null, session), provider.getLastQuota()?.resets)

        failResets = true
        provider.refresh()
        assertEquals(ClaudeResets(listOf(grant), null, session), provider.getLastQuota()?.resets)
        assertNull(provider.getLastError())

        // The failed at-limit read was rate limited, so usage reads pause too.
        provider.refresh()
        assertEquals(2, atLimitReads)

        now += 1.hours
        failResets = false
        percent = 40.0
        provider.refresh()
        assertEquals(2, atLimitReads, "below the limit only the regular read is made")
        assertEquals(ClaudeResets(listOf(grant)), provider.getLastQuota()?.resets)
    }

    @Test
    fun redeemResetLooksUpTheOrganizationOnceAndRetriesWithARefreshedToken() {
        val profileTokens = mutableListOf<String>()
        val claims = mutableListOf<Pair<String, String>>()
        val provider =
            ClaudeQuotaProvider(
                client =
                    FakeClaudeClient(
                        organization = { token ->
                            profileTokens += token
                            ORGANIZATION
                        },
                        redeem = { token, organization ->
                            claims += token to organization
                            if (token == "old") throw ClaudeQuotaException("expired", 401)
                            ClaudeResetOutcome(ClaudeResetOutcome.RESET)
                        },
                    ) {
                        ClaudeQuota()
                    },
                tokenProvider = { "old" },
                tokenRefresher = { "fresh" },
            )
        val choice = ClaudeResetChoice.Session(ClaudeSessionReset(null))

        assertTrue(provider.redeemReset(choice).succeeded)
        assertTrue(provider.redeemReset(choice).succeeded)

        assertEquals(listOf("old"), profileTokens)
        assertEquals(
            listOf("old", "fresh", "old", "fresh").map { it to ORGANIZATION },
            claims,
        )
    }

    private class FakeClaudeClient(
        private val fetchAtLimitResets: () -> ClaudeResets? = { null },
        private val organization: (String) -> String = { ORGANIZATION },
        private val redeem: (String, String) -> ClaudeResetOutcome = { _, _ ->
            error("unexpected reset claim")
        },
        private val fetch: () -> ClaudeQuota,
    ) : ClaudeQuotaClient() {
        override fun fetchQuota(accessToken: String?): ClaudeQuota = fetch()

        override fun fetchAtLimitResets(accessToken: String): ClaudeResets? = fetchAtLimitResets()

        override fun fetchOrganizationUuid(accessToken: String): String = organization(accessToken)

        override fun redeemReset(
            accessToken: String,
            organizationUuid: String,
            choice: ClaudeResetChoice,
        ): ClaudeResetOutcome = redeem(accessToken, organizationUuid)
    }

    private companion object {
        const val ORGANIZATION = "6f7d2a10-3c4b-4d5e-8f90-a1b2c3d4e5f6"
        val START = Instant.parse("2026-10-22T10:00:00Z")
    }
}
