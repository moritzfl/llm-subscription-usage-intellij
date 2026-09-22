package de.moritzf.quota.idea.common

import de.moritzf.quota.antigravity.AntigravityQuotaException
import de.moritzf.quota.antigravity.USAGE_REPORT
import de.moritzf.quota.antigravity.parseAntigravityQuota
import de.moritzf.quota.idea.mcp.UsageQuotaMcpRegistry
import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntigravityQuotaProviderTest {
    @Test
    fun exportsNativeReportThroughMcpWithoutCachingAnonymousCliLogin() {
        val settings = QuotaSettingsState()
        val account = settings.addAccount(QuotaProviderType.ANTIGRAVITY)
        val provider = AntigravityQuotaProvider(account.id) { parseAntigravityQuota(USAGE_REPORT) }
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        val service = QuotaUsageService(
            providers = listOf(provider),
            settingsProvider = { settings },
            scheduler = scheduler,
            updatePublisher = {},
            scheduleOnInit = false,
        )
        try {
            service.refreshBlocking(account.id)
            assertEquals(USAGE_REPORT, UsageQuotaMcpRegistry.get(provider.type).json(service, provider.type))
            assertNotNull(service.getLastQuota(account.id))
            assertNull(settings.cachedQuotaJson(account.id))
            assertTrue(settings.lastUpdate(account.id) > 0)
        } finally {
            service.dispose()
            scheduler.shutdownNow()
        }
    }

    @Test
    fun doesNotRestoreAnotherCliLoginsPersistedQuota() {
        val settings = QuotaSettingsState()
        val provider = AntigravityQuotaProvider { error("Hydration must not execute AGY") }
        settings.setCachedQuotaJson(provider.accountId, QuotaSnapshotCache.encode(provider.type, parseAntigravityQuota(USAGE_REPORT)))
        settings.updateTimestamp(provider.accountId)
        settings.setLastActiveProvider(provider.type)

        provider.hydrateFromCache(settings)

        assertNull(provider.getLastQuota())
        assertNull(provider.getLastRawJson())
        assertNull(provider.cachedUsageFraction(settings))
        assertTrue(provider.cachedActivityWindows(settings).isEmpty())
        assertNull(settings.cachedQuotaJson(provider.accountId))
        assertEquals(0L, settings.lastUpdate(provider.accountId))
        assertNull(settings.lastActiveSource)
    }

    @Test
    fun failedRefreshDropsQuotaBecauseCliMayHaveSwitchedAccount() {
        var failure = false
        val provider = AntigravityQuotaProvider {
            if (failure) throw AntigravityQuotaException("Sign in using agy")
            parseAntigravityQuota(USAGE_REPORT)
        }
        provider.refresh()
        assertNotNull(provider.getLastQuota())

        failure = true
        provider.refresh()

        assertNull(provider.getLastQuota())
        assertNull(provider.getLastRawJson())
        assertEquals("Sign in using agy", provider.getLastError())
    }

    @Test
    fun lateReportCannotRestoreClearedOrRemovedAccount() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = AntigravityQuotaProvider {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            parseAntigravityQuota(USAGE_REPORT)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val refresh = executor.submit { provider.refresh() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            provider.clearData("Account changed")
            release.countDown()
            refresh.get(5, TimeUnit.SECONDS)

            assertNull(provider.getLastQuota())
            assertNull(provider.getLastRawJson())
            assertEquals("Account changed", provider.getLastError())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun interruptedRefreshRestoresInterruptFlag() {
        val provider = AntigravityQuotaProvider { throw InterruptedException() }
        try {
            provider.refresh()
            assertTrue(Thread.currentThread().isInterrupted)
            assertNull(provider.getLastQuota())
            assertEquals("AGY quota refresh cancelled.", provider.getLastError())
        } finally {
            Thread.interrupted()
        }
    }
}
