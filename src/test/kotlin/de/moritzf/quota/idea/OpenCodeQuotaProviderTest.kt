package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.common.OpenCodeQuotaProvider
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeQuotaProviderTest {
    @Test
    fun browserSelectedScopeOverridesOldWorkspace() {
        val settings = QuotaSettingsState().apply { openCodeWorkspaceId = "wrk_old" }
        val requested = mutableListOf<String>()
        val provider = OpenCodeQuotaProvider(
            openCodeClient = object : OpenCodeQuotaClient() {
                override fun discoverWorkspaceId(accessToken: String): String = error("Scoped grants need no discovery")
                override fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
                    requested += workspaceId
                    return OpenCodeQuota(availableBalance = 0)
                }
            },
            credentialsProvider = { OAuthCredentials(accessToken = "token", accountId = "org_selected") },
            settingsProvider = { settings },
        )
        provider.refresh()
        assertEquals(listOf("org_selected"), requested)
        assertEquals("org_selected", settings.openCodeWorkspaceId)
        assertNull(provider.getLastError())
    }

    @Test
    fun permissionAndMalformedResponseFailuresDoNotRefreshOrRediscover() {
        for (status in listOf(200, 403, 404)) {
            val tokens = mutableListOf<String?>()
            var calls = 0
            val provider = OpenCodeQuotaProvider(
                openCodeClient = object : OpenCodeQuotaClient() {
                    override fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
                        calls++
                        throw OpenCodeQuotaException("failed", status, "{}")
                    }
                },
                credentialsProvider = { rejected ->
                    tokens += rejected
                    OAuthCredentials(accessToken = "token", accountId = "org_selected")
                },
                settingsProvider = { null },
            )
            provider.refresh()
            assertEquals(listOf<String?>(null), tokens)
            assertEquals(1, calls)
            assertEquals("failed", provider.getLastError())
        }
    }

    @Test
    fun quotaFinishingAfterLogoutCannotRestoreClearedSnapshot() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = OpenCodeQuotaProvider(
            openCodeClient = object : OpenCodeQuotaClient() {
                override fun fetchQuota(accessToken: String, workspaceId: String): OpenCodeQuota {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return OpenCodeQuota(availableBalance = 123)
                }
            },
            credentialsProvider = { OAuthCredentials(accessToken = "token", accountId = "org_selected") },
            settingsProvider = { null },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val refresh = executor.submit { provider.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            provider.clearData("Not signed in to OpenCode")
            release.countDown()
            refresh.get(5, TimeUnit.SECONDS)
            assertNull(provider.getLastQuota())
            assertEquals("Not signed in to OpenCode", provider.getLastError())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
