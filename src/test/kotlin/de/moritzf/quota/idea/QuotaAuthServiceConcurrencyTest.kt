package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.OAuthClientConfig
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthTokenOperations
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaAuthServiceConcurrencyTest {
    @Test
    fun logoutDuringRefreshDiscardsRefreshedCredentials() {
        val store = InMemoryCredentialStore(expiredCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
        val refreshStarted = CountDownLatch(1)
        val allowRefreshToFinish = CountDownLatch(1)
        val refreshCalls = AtomicInteger(0)
        val refreshedCredentials = validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    refreshStarted.countDown()
                    assertTrue(allowRefreshToFinish.await(5, TimeUnit.SECONDS))
                    refreshedCredentials
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val tokenFuture = executor.submit<String?> { service.getAccessTokenBlocking() }

            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            service.clearCredentials()
            allowRefreshToFinish.countDown()

            assertNull(tokenFuture.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCalls.get())
            assertNull(store.current())
            assertFalse(service.isLoggedIn())
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun concurrentAccessRefreshesExpiredCredentialsOnlyOnce() {
        val store = InMemoryCredentialStore(expiredCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
        val refreshStarted = CountDownLatch(1)
        val allowRefreshToFinish = CountDownLatch(1)
        val refreshCalls = AtomicInteger(0)
        val refreshedCredentials = validCredentials(accessToken = "shared-token", refreshToken = "shared-refresh-token")
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    refreshStarted.countDown()
                    assertTrue(allowRefreshToFinish.await(5, TimeUnit.SECONDS))
                    refreshedCredentials
                },
            ),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstToken = executor.submit<String?> { service.getAccessTokenBlocking() }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))

            val secondToken = executor.submit<String?> { service.getAccessTokenBlocking() }
            allowRefreshToFinish.countDown()

            assertEquals("shared-token", firstToken.get(5, TimeUnit.SECONDS))
            assertEquals("shared-token", secondToken.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCalls.get())
            assertEquals("shared-token", store.current()?.accessToken)
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    private fun createService(
        store: OAuthCredentialStore,
        tokenOperations: OAuthTokenOperations,
    ): QuotaAuthService {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return QuotaAuthService(
            scope = testScope,
            httpClient = HttpClient(CIO),
            tokenOperations = tokenOperations,
            credentialStore = store,
            loginFlowStarter = { _: OAuthClientConfig -> error("Login flow should not be started in this test") },
            browserOpener = {},
        )
    }

    private fun expiredCredentials(accessToken: String, refreshToken: String): OAuthCredentials {
        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() - 60_000,
            accountId = "account-1",
        )
    }

    private fun validCredentials(accessToken: String, refreshToken: String): OAuthCredentials {
        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAt = System.currentTimeMillis() + 10 * 60_000,
            accountId = "account-1",
        )
    }

    private class InMemoryCredentialStore(initial: OAuthCredentials?) : OAuthCredentialStore {
        private val credentials = AtomicReference(copyCredentials(initial))

        override fun load(): OAuthCredentials? = copyCredentials(credentials.get())

        override fun save(credentials: OAuthCredentials) {
            this.credentials.set(copyCredentials(credentials))
        }

        override fun clear() {
            credentials.set(null)
        }

        fun current(): OAuthCredentials? = copyCredentials(credentials.get())

        companion object {
            private fun copyCredentials(credentials: OAuthCredentials?): OAuthCredentials? {
                return credentials?.let {
                    OAuthCredentials(
                        accessToken = it.accessToken,
                        refreshToken = it.refreshToken,
                        expiresAt = it.expiresAt,
                        accountId = it.accountId,
                    )
                }
            }
        }
    }

    private class TestTokenOperations(
        private val onRefresh: (OAuthCredentials) -> OAuthCredentials,
    ) : OAuthTokenOperations {
        override suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String): OAuthCredentials {
            error("Authorization-code exchange should not be used in this test")
        }

        override suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
            return onRefresh(existing)
        }
    }
}
