package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.minimax.MiniMaxCodeAccount
import de.moritzf.quota.minimax.MiniMaxCodeAccountClient
import de.moritzf.quota.minimax.MiniMaxCredits
import de.moritzf.quota.minimax.MiniMaxQuota
import de.moritzf.quota.minimax.MiniMaxQuotaClient
import de.moritzf.quota.minimax.MiniMaxQuotaException
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxUsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class MiniMaxQuotaProviderTest {
    @Test
    fun aCreditsOnlyAccountIsStoredWithoutTryingTheOtherRegion() {
        val requested = mutableListOf<MiniMaxRegion>()
        val provider =
            provider(
                FakeMiniMaxClient { region ->
                    requested += region
                    MiniMaxQuota(region = region, planActive = false, rawJson = "{}")
                },
            )

        provider.refresh()

        assertEquals(listOf(MiniMaxRegion.GLOBAL), requested)
        assertFalse(provider.getLastQuota()!!.planActive)
        assertNull(provider.getLastError())
    }

    @Test
    fun theOtherRegionRejectingTheKeyDoesNotHideTheRealFailure() {
        val provider =
            provider(
                FakeMiniMaxClient { region ->
                    when (region) {
                        MiniMaxRegion.GLOBAL ->
                            throw MiniMaxQuotaException("Request failed (HTTP 503).", 503, "global")
                        MiniMaxRegion.CN ->
                            throw MiniMaxQuotaException(
                                "Session expired. Check your MiniMax subscription key.",
                                401,
                                "cn",
                            )
                    }
                },
            )

        provider.refresh()

        assertEquals("Request failed (HTTP 503).", provider.getLastError())
        assertEquals("global", provider.getLastRawJson())
    }

    @Test
    fun aKeyBothRegionsRejectIsReportedAsRejected() {
        val provider =
            provider(
                FakeMiniMaxClient {
                    throw MiniMaxQuotaException(
                        "Session expired. Check your MiniMax subscription key.",
                        401,
                    )
                },
            )

        provider.refresh()

        assertEquals(
            "Session expired. Check your MiniMax subscription key.",
            provider.getLastError(),
        )
    }

    @Test
    fun theLoginAddsCreditsToTheKeysReading() {
        val provider =
            provider(
                FakeMiniMaxClient { region ->
                    MiniMaxQuota(region = region, planActive = false, rawJson = NO_PLAN)
                },
                login = FakeLogin(),
                account =
                    FakeAccountClient { _, includeTokenPlan ->
                        assertFalse(includeTokenPlan, "the key already reads the plan")
                        ACCOUNT
                    },
            )

        provider.refresh()

        val quota = assertNotNull(provider.getLastQuota())
        assertFalse(quota.planActive)
        assertEquals(MiniMaxCredits(3944.267, 3144.267, 800.0), quota.credits)
        assertTrue(quota.warnings.isEmpty())
        val raw = Json.parseToJsonElement(provider.getLastRawJson()!!).jsonObject
        assertEquals(
            setOf("subscription_key", "minimax_code"),
            raw.keys,
        )
        assertTrue("token_plan" in raw.getValue("subscription_key").jsonObject)
        assertTrue("membership" in raw.getValue("minimax_code").jsonObject)
    }

    @Test
    fun theLoginAloneReadsThePlanAndCredits() {
        val provider =
            provider(
                FakeMiniMaxClient { error("no key, no key request") },
                apiKey = null,
                login = FakeLogin(),
                account =
                    FakeAccountClient { _, includeTokenPlan ->
                        assertTrue(includeTokenPlan)
                        MiniMaxCodeAccount(
                            membershipJson = """{"has_token_plan":true}""",
                            hasTokenPlan = true,
                            credits = MiniMaxCredits(12.0),
                            tokenPlanJson =
                                """{"model_remains":[{"model_name":"general","current_interval_remaining_percent":75}],"base_resp":{"status_code":0}}""",
                        )
                    },
            )

        provider.refresh()

        val quota = assertNotNull(provider.getLastQuota())
        assertTrue(quota.planActive)
        assertEquals(25.0, quota.sessionUsage?.usagePercent)
        assertEquals(12.0, quota.credits?.remaining)
        val raw = Json.parseToJsonElement(provider.getLastRawJson()!!).jsonObject
        assertEquals(setOf("minimax_code"), raw.keys)
    }

    @Test
    fun aRejectedLoginTokenIsRefreshedOnce() {
        val tokens = mutableListOf<String>()
        val login = FakeLogin()
        val provider =
            provider(
                FakeMiniMaxClient { error("no key") },
                apiKey = null,
                login = login,
                account =
                    FakeAccountClient { token, _ ->
                        tokens += token
                        if (token == "at-1") throw MiniMaxQuotaException("expired", 401)
                        ACCOUNT
                    },
            )

        provider.refresh()

        assertEquals(listOf("at-1", "at-2"), tokens)
        assertEquals(listOf("at-1"), login.refreshed)
        assertTrue(login.reconnect.isEmpty())
        assertEquals(3944.267, provider.getLastQuota()?.credits?.remaining)
    }

    @Test
    fun aLoginTheServerKeepsRejectingNeedsReconnecting() {
        val login = FakeLogin()
        val provider =
            provider(
                FakeMiniMaxClient { error("no key") },
                apiKey = null,
                login = login,
                account =
                    FakeAccountClient { _, _ ->
                        throw MiniMaxQuotaException("MiniMax Code login expired.", 401)
                    },
            )

        provider.refresh()

        assertEquals(listOf("at-2"), login.reconnect)
        assertEquals("MiniMax Code login expired.", provider.getLastError())
        assertTrue(provider.getLastRawJson()!!.contains("\"error\""))
    }

    @Test
    fun aFailingLoginKeepsTheKeysReadingWithAWarning() {
        val provider =
            provider(
                FakeMiniMaxClient { region ->
                    MiniMaxQuota(
                        region = region,
                        sessionUsage = MiniMaxUsageWindow(usagePercent = 10.0),
                        rawJson = "{}",
                    )
                },
                login = FakeLogin(),
                account =
                    FakeAccountClient { _, _ ->
                        throw MiniMaxQuotaException("Request failed. Check your connection.")
                    },
            )

        provider.refresh()

        val quota = assertNotNull(provider.getLastQuota())
        assertEquals(10.0, quota.sessionUsage?.usagePercent)
        assertNull(quota.credits)
        assertEquals(
            listOf("MiniMax Code: Request failed. Check your connection."),
            quota.warnings,
        )
        assertNull(provider.getLastError())
    }

    @Test
    fun withoutKeyAndLoginNothingIsConfigured() {
        val provider = provider(FakeMiniMaxClient { error("no key") }, apiKey = null)

        provider.refresh()

        assertNull(provider.getLastQuota())
        assertEquals(provider.notConfiguredMessage, provider.getLastError())
    }

    private fun provider(
        client: MiniMaxQuotaClient,
        apiKey: String? = "key",
        login: MiniMaxCodeLogin = FakeLogin(loggedIn = false),
        account: MiniMaxCodeAccountClient = FakeAccountClient { _, _ -> error("not signed in") },
    ) =
        MiniMaxQuotaProvider(
            client = client,
            settingsProvider = { null },
            apiKeyProvider = { apiKey },
            codeClient = account,
            codeLogin = login,
        )

    private class FakeMiniMaxClient(private val fetch: (MiniMaxRegion) -> MiniMaxQuota) :
        MiniMaxQuotaClient() {
        override fun fetchQuota(apiKey: String, region: MiniMaxRegion): MiniMaxQuota = fetch(region)
    }

    private class FakeAccountClient(
        private val fetch: (token: String, includeTokenPlan: Boolean) -> MiniMaxCodeAccount,
    ) : MiniMaxCodeAccountClient() {
        override fun fetchUserId(accessToken: String, region: MiniMaxRegion) = "user"

        override fun fetchAccount(
            accessToken: String,
            region: MiniMaxRegion,
            userId: String,
            includeTokenPlan: Boolean,
        ): MiniMaxCodeAccount = fetch(accessToken, includeTokenPlan)
    }

    private class FakeLogin(private val loggedIn: Boolean = true) : MiniMaxCodeLogin {
        val refreshed = mutableListOf<String>()
        val reconnect = mutableListOf<String>()

        override fun isLoggedIn() = loggedIn

        override fun accessToken() = "at-1"

        override fun refreshAfterRejection(rejectedAccessToken: String): String {
            refreshed += rejectedAccessToken
            return "at-2"
        }

        override fun requireReconnect(rejectedAccessToken: String) {
            reconnect += rejectedAccessToken
        }

        override fun connectionState() = OAuthConnectionState.CONNECTED

        override fun userId() = "575100920747536392"

        override fun region() = MiniMaxRegion.GLOBAL
    }

    private companion object {
        const val NO_PLAN =
            """{"model_remains":null,"base_resp":{"status_code":2062,"status_msg":"no active token plan subscription"}}"""
        val ACCOUNT =
            MiniMaxCodeAccount(
                membershipJson = """{"has_token_plan":false}""",
                hasTokenPlan = false,
                credits = MiniMaxCredits(3944.267, 3144.267, 800.0),
            )
    }
}
