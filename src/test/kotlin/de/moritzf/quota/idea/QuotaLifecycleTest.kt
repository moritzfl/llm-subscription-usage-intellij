package de.moritzf.quota.idea

import de.moritzf.quota.idea.common.OpenAiQuotaProvider
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.openai.OpenAiCodexQuota
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaLifecycleTest {
    @Test
    fun invalidatedRefreshCannotPublishOrRestoreCache() {
        for (action in listOf("remove", "dispose", "clear")) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val published = AtomicInteger()
            val settings =
                QuotaSettingsState().apply {
                    settingsVersion = 3
                    accounts = mutableListOf(ProviderAccount(id = "openai", typeId = "openai"))
                }
            val provider =
                OpenAiQuotaProvider(
                    accessTokenProvider = { "token" },
                    accountIdProvider = { null },
                    quotaFetcher = { _, _ ->
                        entered.countDown()
                        // Simulate a transport that completes despite interruption.
                        while (true) {
                            try {
                                check(release.await(5, TimeUnit.SECONDS))
                                break
                            } catch (_: InterruptedException) {}
                        }
                        OpenAiCodexQuota(allowed = true)
                    },
                )
            val service =
                QuotaUsageService(
                    providers = listOf(provider),
                    settingsProvider = { settings },
                    updatePublisher = { published.incrementAndGet() },
                    scheduleOnInit = false,
                )
            val executor = Executors.newSingleThreadExecutor()
            try {
                val refresh = executor.submit { service.refreshBlocking("openai") }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                when (action) {
                    "remove" -> {
                        settings.accounts.clear()
                        service.syncAccounts()
                    }
                    "dispose" -> service.dispose()
                    else -> service.clearUsageData("openai")
                }
                val before = published.get()
                release.countDown()
                refresh.get(5, TimeUnit.SECONDS)
                assertEquals(before, published.get(), action)
                assertNull(settings.cachedQuotaJson("openai"), action)
                assertEquals(0L, settings.lastUpdate("openai"), action)
                assertNull(provider.getLastQuota(), action)
            } finally {
                release.countDown()
                service.dispose()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun concurrentCacheUpdatesProduceDetachedConsistentSnapshots() {
        val settings = QuotaSettingsState()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val tasks =
                (0 until 4).map { worker ->
                    executor.submit {
                        repeat(100) { index ->
                            settings.storeQuotaSnapshot("$worker-$index", "{}")
                            val snapshot = settings.getState()
                            assertEquals(
                                snapshot.cachedQuotaJsons.keys,
                                snapshot.lastProviderUpdates.keys,
                            )
                        }
                    }
                }
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
            val snapshot = settings.getState()
            assertEquals(400, snapshot.cachedQuotaJsons.size)
            settings.dropAccountData("0-0")
            assertEquals("{}", snapshot.cachedQuotaJson("0-0"))
        } finally {
            executor.shutdownNow()
        }
    }
}
