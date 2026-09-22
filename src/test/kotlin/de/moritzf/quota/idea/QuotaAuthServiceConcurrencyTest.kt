package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.auth.OAuthClientConfig
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentialCoordinator
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.LoginResult
import de.moritzf.quota.idea.auth.OAuthTokenRequestException
import de.moritzf.quota.idea.auth.OAuthTokenOperations
import de.moritzf.quota.idea.common.QuotaProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
    fun logoutDuringRejectedRefreshDoesNotRestoreCredentialsOnRetry() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val firstRefreshStarted = CountDownLatch(1)
        val allowFirstRefreshToFail = CountDownLatch(1)
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    if (refreshCalls.incrementAndGet() == 1) {
                        firstRefreshStarted.countDown()
                        assertTrue(allowFirstRefreshToFail.await(5, TimeUnit.SECONDS))
                        throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                    }
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val tokenFuture = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }

            assertTrue(firstRefreshStarted.await(5, TimeUnit.SECONDS))
            service.clearCredentials(QuotaProviderType.OPEN_AI)
            allowFirstRefreshToFail.countDown()

            assertNull(tokenFuture.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCalls.get(), "logout must prevent the invalid_grant retry")
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
    fun concurrentAccessSharesRefreshFailureWithoutReplayingToken() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshStarted = CountDownLatch(1)
        val allowRefreshToFail = CountDownLatch(1)
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    refreshStarted.countDown()
                    assertTrue(allowRefreshToFail.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("connection reset")
                },
            ),
        )
        val executor = Executors.newFixedThreadPool(2)

        try {
            val firstToken = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
            val secondToken = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }
            allowRefreshToFail.countDown()

            assertNull(firstToken.get(5, TimeUnit.SECONDS))
            assertNull(secondToken.get(5, TimeUnit.SECONDS))
            assertEquals(1, refreshCalls.get(), "waiting callers must share a failed refresh outcome")
            assertEquals("old-token", store.current()?.accessToken)
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
    fun credentialSaveFailureKeepsRotatedTokenInMemory() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val failingStore = object : OAuthCredentialStore by store {
            override fun save(credentials: OAuthCredentials) {
                throw IllegalStateException("Password Safe unavailable")
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )

        try {
            assertEquals("new-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals("new-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get(), "the stale stored refresh token must not be reused")
            assertEquals("old-token", store.current()?.accessToken, "store stays stale when Password Safe fails")
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun chainedMemoryOnlyRotationsKeepNewestCredentials() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val failingStore = object : OAuthCredentialStore by store {
            override fun save(credentials: OAuthCredentials) {
                throw IllegalStateException("Password Safe unavailable")
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    if (refreshCalls.incrementAndGet() == 1) {
                        validCredentials(accessToken = "token-a", refreshToken = "refresh-a")
                    } else {
                        validCredentials(accessToken = "token-b", refreshToken = "refresh-b")
                    }
                },
            ),
        )

        try {
            assertEquals("token-a", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(
                "token-b",
                service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "token-a"),
            )
            assertEquals("token-b", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(2, refreshCalls.get())
            assertEquals("old-token", store.current()?.accessToken)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun pendingRotationTracksExternallyAdoptedPersistedAncestor() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val failingStore = object : OAuthCredentialStore by store {
            override fun save(credentials: OAuthCredentials) {
                throw IllegalStateException("Password Safe unavailable")
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(
                onRefresh = { existing ->
                    when (refreshCalls.incrementAndGet()) {
                        1 -> validCredentials(accessToken = "token-a", refreshToken = "refresh-a")
                        2 -> {
                            store.save(
                                OAuthCredentials(
                                    accessToken = "token-x",
                                    refreshToken = "refresh-x",
                                    expiresAt = System.currentTimeMillis() - 60_000,
                                    accountId = "account-1",
                                )
                            )
                            throw OAuthTokenRequestException(
                                "invalid grant for ${existing.refreshToken}",
                                400,
                                "invalid_grant",
                            )
                        }
                        else -> validCredentials(accessToken = "token-b", refreshToken = "refresh-b")
                    }
                },
            ),
        )

        try {
            assertEquals("token-a", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(
                "token-b",
                service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "token-a"),
            )
            assertEquals("token-b", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(3, refreshCalls.get())
            assertEquals("token-x", store.current()?.accessToken)
        } finally {
            service.dispose()
        }
    }

    @Test
    fun logoutWinsWhenPendingCredentialReloadFails() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val failNextLoad = AtomicBoolean(false)
        val pendingLoadStarted = CountDownLatch(1)
        val allowPendingLoadToFail = CountDownLatch(1)
        val failingStore = object : OAuthCredentialStore by store {
            override fun load(): OAuthCredentials? {
                if (failNextLoad.compareAndSet(true, false)) {
                    pendingLoadStarted.countDown()
                    assertTrue(allowPendingLoadToFail.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("Password Safe unavailable")
                }
                return store.load()
            }

            override fun save(credentials: OAuthCredentials) {
                throw IllegalStateException("Password Safe unavailable")
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            assertEquals("new-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            failNextLoad.set(true)
            val pendingToken = executor.submit<String?> {
                service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI)
            }
            assertTrue(pendingLoadStarted.await(5, TimeUnit.SECONDS))

            assertTrue(service.clearCredentials(QuotaProviderType.OPEN_AI))
            allowPendingLoadToFail.countDown()

            assertNull(pendingToken.get(5, TimeUnit.SECONDS))
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun loginPersistenceFailureReturnsErrorAndKeepsLoginDisconnected() {
        val store = InMemoryCredentialStore(null)
        val failingStore = object : OAuthCredentialStore by store {
            override fun save(credentials: OAuthCredentials) {
                throw IllegalStateException("Password Safe unavailable")
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(
                onRefresh = { error("Refresh must not run") },
                onExchange = { _, _, _ ->
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )
        val authUrl = AtomicReference<String>()
        val result = AtomicReference<LoginResult>()
        val completed = CountDownLatch(1)

        try {
            service.startLoginFlow(
                type = QuotaProviderType.CLAUDE,
                callback = {
                    result.set(it)
                    completed.countDown()
                },
                onAuthUrl = authUrl::set,
            )
            val state = QuotaAuthService.parseQuery(URI.create(authUrl.get()).rawQuery).getValue("state")
            assertNull(service.completePastedCallback(QuotaProviderType.CLAUDE, "code#$state"))

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertFalse(result.get().success)
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.CLAUDE))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun cancelDuringCodeExchangePreventsCredentialPersistence() {
        val store = InMemoryCredentialStore(null)
        val exchangeStarted = CountDownLatch(1)
        val allowExchangeToFinish = CountDownLatch(1)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = { error("Refresh must not run") },
                onExchange = { _, _, _ ->
                    exchangeStarted.countDown()
                    assertTrue(allowExchangeToFinish.await(5, TimeUnit.SECONDS))
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )
        val authUrl = AtomicReference<String>()
        val executor = Executors.newSingleThreadExecutor()

        try {
            service.startLoginFlow(
                type = QuotaProviderType.CLAUDE,
                callback = {},
                onAuthUrl = authUrl::set,
            )
            val state = QuotaAuthService.parseQuery(URI.create(authUrl.get()).rawQuery).getValue("state")
            val completion = executor.submit<String?> {
                service.completePastedCallback(QuotaProviderType.CLAUDE, "code#$state")
            }
            assertTrue(exchangeStarted.await(5, TimeUnit.SECONDS))

            assertTrue(service.abortLogin(QuotaProviderType.CLAUDE, "Login canceled"))
            allowExchangeToFinish.countDown()
            assertNull(completion.get(5, TimeUnit.SECONDS))

            assertNull(store.current())
            assertFalse(service.isLoginInProgress(QuotaProviderType.CLAUDE))
            assertFalse(service.isLoggedIn(QuotaProviderType.CLAUDE))
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun terminalRefreshFailureKeepsStoredCredentials() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get())
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun credentialLoadFailureKeepsCachedCredentials() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "stored-token", refreshToken = "stored-refresh"))
        val failLoads = AtomicBoolean(false)
        val failingStore = object : OAuthCredentialStore by store {
            override fun load(): OAuthCredentials? {
                if (failLoads.get()) throw IllegalStateException("Password Safe unavailable")
                return store.load()
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(onRefresh = { error("Refresh must not run") }),
        )

        try {
            assertEquals("stored-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            failLoads.set(true)
            assertEquals("stored-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun logoutWinsWhenCredentialLoadFailsConcurrently() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "stored-token", refreshToken = "stored-refresh"))
        val failNextLoad = AtomicBoolean(false)
        val loadStarted = CountDownLatch(1)
        val allowLoadToFail = CountDownLatch(1)
        val failingStore = object : OAuthCredentialStore by store {
            override fun load(): OAuthCredentials? {
                if (failNextLoad.compareAndSet(true, false)) {
                    loadStarted.countDown()
                    assertTrue(allowLoadToFail.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("Password Safe unavailable")
                }
                return store.load()
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(onRefresh = { error("Refresh must not run") }),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            assertEquals("stored-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            failNextLoad.set(true)
            val token = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI) }
            assertTrue(loadStarted.await(5, TimeUnit.SECONDS))

            assertTrue(service.clearCredentials(QuotaProviderType.OPEN_AI))
            allowLoadToFail.countDown()

            assertNull(token.get(5, TimeUnit.SECONDS))
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun credentialClearFailureKeepsLogin() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "stored-token", refreshToken = "stored-refresh"))
        val failingStore = object : OAuthCredentialStore by store {
            override fun clear() {
                throw IllegalStateException("Password Safe unavailable")
            }
        }
        val service = createService(
            store = failingStore,
            tokenOperations = TestTokenOperations(onRefresh = { error("Refresh must not run") }),
        )

        try {
            assertEquals("stored-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertFalse(service.clearCredentials(QuotaProviderType.OPEN_AI))
            assertEquals("stored-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun terminalRefreshFailureAdoptsNonExpiredCredentialsRotatedByAnotherIde() {
        // The IDE credential store is shared between JetBrains IDEs: a second IDE can rotate the
        // refresh token while ours is in flight. The rejection then only means our copy was stale.
        // Adopt the usable snapshot instead of burning the other IDE's refresh token again.
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    store.save(validCredentials(accessToken = "other-ide-token", refreshToken = "other-ide-refresh"))
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertEquals("other-ide-token", service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get(), "must not second-refresh a usable other-IDE login")
            assertEquals("other-ide-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
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
            credentialStoreFactory = { _, type -> stores[type] ?: InMemoryCredentialStore(null) },
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
    fun invalidGrantWithUnchangedStoredCredentialsDoesNotReplayOrLogout() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get(), "an unchanged rejected token must not be replayed")
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun missingSharedCredentialsAbortInvalidGrantRetry() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    if (refreshCalls.incrementAndGet() == 1) {
                        store.clear()
                        throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                    }
                    validCredentials(accessToken = "new-token", refreshToken = "new-refresh-token")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get(), "a removed shared login must not be recreated")
            assertNull(store.current())
            assertFalse(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun serverErrorLabeledInvalidGrantKeepsLoginWithoutRetry() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    throw OAuthTokenRequestException("upstream failure", 503, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get(), "a 5xx response must not replay a rotating token")
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun recentInvalidGrantSuppressesImmediateRefreshRetry() {
        val store = InMemoryCredentialStore(expiredCredentials())
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.OPEN_AI))
            assertEquals(1, refreshCalls.get(), "a recent failed refresh must be shared by immediate callers")
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
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
    fun forceRefreshInvalidGrantDoesNotReplayOrLogout() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token"))
            assertEquals(1, refreshCalls.get())
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun repeatedForceRefreshFailureKeepsCredentialsAndUsesBackoff() {
        val store = InMemoryCredentialStore(validCredentials(accessToken = "old-token", refreshToken = "refresh-token"))
        val refreshCalls = AtomicInteger(0)
        val service = createService(
            store = store,
            tokenOperations = TestTokenOperations(
                onRefresh = {
                    refreshCalls.incrementAndGet()
                    throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
                },
            ),
        )

        try {
            assertNull(service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token"))
            assertNull(service.forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleAccessToken = "old-token"))
            assertEquals(1, refreshCalls.get())
            assertEquals("old-token", store.current()?.accessToken)
            assertTrue(service.isLoggedIn(QuotaProviderType.OPEN_AI))
        } finally {
            service.dispose()
        }
    }

    @Test
    fun secondAccountOfSameTypeCannotStartLoginWhileFirstRuns() {
        val service = createService(
            store = InMemoryCredentialStore(null),
            tokenOperations = TestTokenOperations(
                onRefresh = { error("Refresh must not run") },
            ),
        )
        val second = AtomicReference<LoginResult>()
        try {
            service.startLoginFlow(
                accountId = QuotaProviderType.CLAUDE.id,
                type = QuotaProviderType.CLAUDE,
                callback = {},
                onAuthUrl = {},
            )
            assertTrue(service.isLoginInProgress(QuotaProviderType.CLAUDE.id, QuotaProviderType.CLAUDE))
            service.startLoginFlow(
                accountId = "extra-claude",
                type = QuotaProviderType.CLAUDE,
                callback = { second.set(it) },
                onAuthUrl = {},
            )
            assertFalse(second.get().success)
            assertTrue(second.get().message!!.contains("Finish or cancel the other"))
        } finally {
            service.abortLogin(QuotaProviderType.CLAUDE.id, QuotaProviderType.CLAUDE, "test")
            service.abortLogin("extra-claude", QuotaProviderType.CLAUDE, "test")
            service.dispose()
        }
    }

    private fun createService(
        store: OAuthCredentialStore,
        tokenOperations: OAuthTokenOperations,
    ): QuotaAuthService {
        return createService(
            credentialStoreFactory = { _, _ -> store },
            tokenOperations = tokenOperations,
        )
    }

    private fun createService(
        credentialStoreFactory: (String, QuotaProviderType) -> OAuthCredentialStore,
        tokenOperations: OAuthTokenOperations,
    ): QuotaAuthService {
        val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return QuotaAuthService(
            scope = testScope,
            httpClient = HttpClient.newHttpClient(),
            tokenOperationsFactory = { _, _ -> tokenOperations },
            credentialStoreFactory = credentialStoreFactory,
            browserOpener = {},
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
        override val coordinator = OAuthCredentialCoordinator()
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
        private val onExchange: (String, String, String?) -> OAuthCredentials = { _, _, _ ->
            error("Authorization-code exchange should not be used in this test")
        },
    ) : OAuthTokenOperations {
        override suspend fun exchangeAuthorizationCode(
            code: String,
            codeVerifier: String,
            state: String?,
        ): OAuthCredentials {
            return onExchange(code, codeVerifier, state)
        }

        override suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
            return onRefresh(existing)
        }
    }
}
