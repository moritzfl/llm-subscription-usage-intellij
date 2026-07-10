package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.auth.OAuthClientConfig
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthTokenRequestException
import de.moritzf.quota.idea.auth.OAuthTokenOperations
import de.moritzf.quota.idea.common.QuotaProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.http.HttpClient
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
        val store = InMemoryCredentialStore(expiredCredentials())
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
            val tokenFuture = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }

            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            service.clearCredentials(QuotaProviderType.OPEN_AI)
            allowRefreshToFinish.countDown()

            assertNull(tokenFuture.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCalls.get())
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun concurrentAccessRefreshesExpiredCredentialsOnlyOnce() {
        val store = InMemoryCredentialStore(expiredCredentials())
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
            val firstToken = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))

            val secondToken = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }
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

    @Test
    fun transientRefreshFailureKeepsStoredCredentials() {
        val existing = expiredCredentials()
        val store = InMemoryCredentialStore(existing)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    throw IllegalStateException("timeout")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun terminalRefreshFailureClearsStoredCredentials() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun nonTerminalOauthRefreshFailureKeepsStoredCredentials() {
        val existing = expiredCredentials()
        val store = InMemoryCredentialStore(existing)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    throw OAuthTokenRequestException("invalid request", 400, "invalid_request")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun isLoggedInLoadsPersistedCredentialsOnFirstAccess() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "persisted-token", refreshToken = "persisted-refresh-token"))
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = { error("Refresh should not be called for valid credentials") },
            ),
        )

        try {
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
            assertEquals("account-1", service.getAccountId(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun clearingSuperGrokCredentialsDoesNotClearOpenAiCredentials() {
        val openAiStore = InMemoryCredentialStore(validCredentials(accessToken = "openai-token", refreshToken = "openai-refresh"))
        val superGrokStore = InMemoryCredentialStore(validCredentials(accessToken = "grok-token", refreshToken = "grok-refresh"))
        val stores = mapOf(
            QuotaProviderType.OPEN_AI to openAiStore,
            QuotaProviderType.SUPERGROK to superGrokStore,
        )
        val service = createService(
            credentialStoreFactory = { type -> stores[type] ?: InMemoryCredentialStore(null) },
            tokenOperations = TestTokenOperations(
                onRefresh = { error("Refresh should not be called for valid credentials") },
            ),
        )

        try {
            assertEquals("openai-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals("grok-token", service.getAccessTokenBlocking(QuotaProviderType.SUPERGROK))

            service.clearCredentials(QuotaProviderType.SUPERGROK)

            assertEquals("openai-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals("openai-token", openAiStore.current()?.accessToken)
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.SUPERGROK))
            assertNull(superGrokStore.current())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun concurrentForceRefreshForSameRejectedTokenRefreshesOnlyOnce() {
        // Upstream-401 scenario: credentials are locally valid, but Codex rejected them.
        val store = InMemoryCredentialStore(validCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
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
        val executor = Executors.newFixedThreadPool(2)

        try {
            // Both requests were rejected while carrying the same token, so both report
            // the same stale value; only the first may trigger an actual refresh.
            val first = executor.submit<String?> {
                service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token")
            }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            val second = executor.submit<String?> {
                service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token")
            }
            allowRefreshToFinish.countDown()

            assertEquals("new-token", first.get(5, TimeUnit.SECONDS))
            assertEquals("new-token", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCalls.get())
            assertEquals("new-token", store.current()?.accessToken)
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun forceRefreshSkipsWhenAnotherRequestAlreadyRotatedTheToken() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "current-token", refreshToken = "refresh-token"))
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = { error("Refresh must not run when the rejected token is already rotated away") },
            ),
        )

        try {
            val token = service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "rotated-away-token")
            assertEquals("current-token", token)
            assertEquals("current-token", store.current()?.accessToken)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun forceRefreshRefreshesLocallyValidCredentialsThatUpstreamRejected() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )

        try {
            val token = service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token")
            assertEquals("new-token", token)
            assertEquals(1, refreshCalls.get())
            assertEquals("new-token", store.current()?.accessToken)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun forceRefreshWithUnknownStaleTokenStillRefreshes() {
        // When the rejected Authorization header could not be parsed, the conservative
        // fallback is to refresh anyway rather than retry with a doomed token.
        val store = InMemoryCredentialStore(validCredentials(accessToken = "current-token", refreshToken = "refresh-token"))
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )

        try {
            val token = service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = null)
            assertEquals("new-token", token)
            assertEquals(1, refreshCalls.get())
        } finally {
            service.dispose()
        }
    }

    @Test
    fun forceRefreshTerminalFailureClearsCredentials() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token"))
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    private fun createService(
        store: OAuthCredentialStore,
        tokenOperations: OAuthTokenOperations,
    ): QuotaAuthService {
        return createService(
            credentialStoreFactory = { store },
            tokenOperations = tokenOperations,
        )
    }

    private fun createService(
        credentialStoreFactory: (QuotaProviderType) -> OAuthCredentialStore,
        tokenOperations: OAuthTokenOperations,
    ): QuotaAuthService {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return QuotaAuthService(
            scope = testScope,
            httpClient = HttpClient.newHttpClient(),
            tokenOperationsFactory = { _, _ -> tokenOperations },
            credentialStoreFactory = credentialStoreFactory,
        )
    }

    private fun expiredCredentials(): OAuthCredentials {
        return OAuthCredentials(
            accessToken = "old-token",
            refreshToken = "refresh-token",
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
        override suspend fun exchangeAuthorizationCode(
            code: String,
            codeVerifier: String,
            state: String?,
        ): OAuthCredentials {
            error("Authorization-code exchange should not be used in this test")
        }

        override suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
            return onRefresh(existing)
        }
    }
}
