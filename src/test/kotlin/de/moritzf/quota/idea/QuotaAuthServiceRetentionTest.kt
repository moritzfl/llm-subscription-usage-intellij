package de.moritzf.quota.idea

import com.intellij.credentialStore.Credentials
import de.moritzf.quota.idea.auth.OAuthCredentialCoordinator
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthCredentialsStore
import de.moritzf.quota.idea.auth.OAuthTokenOperations
import de.moritzf.quota.idea.auth.OAuthTokenRequestException
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaAuthServiceRetentionTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun separateServicesShareSuccessfulRefresh() = concurrentRefresh(rejected = false)

    @Test
    fun separateServicesShareRejectedRefresh() = concurrentRefresh(rejected = true)

    private fun concurrentRefresh(rejected: Boolean) {
        val current = AtomicReference<OAuthCredentials?>(credentials("expired", -60_000))
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        val refresh: (OAuthCredentials) -> OAuthCredentials = {
            calls.incrementAndGet()
            started.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
            if (rejected) throw OAuthTokenRequestException("invalid grant", 400, "invalid_grant")
            credentials("rotated")
        }
        val first = service(Store(current, OAuthCredentialCoordinator(directory)), refresh)
        val second = service(Store(current, OAuthCredentialCoordinator(directory)), refresh)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val a = executor.submit<String?> { first.getAccessTokenBlocking(QuotaProviderType.CLAUDE) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val b = executor.submit<String?> { second.getAccessTokenBlocking(QuotaProviderType.CLAUDE) }
            finish.countDown()
            val expected = if (rejected) null else "rotated"
            assertEquals(expected, a.get(5, TimeUnit.SECONDS))
            assertEquals(expected, b.get(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get(), "independent services must not submit the same refresh token twice")
        } finally {
            finish.countDown()
            executor.shutdownNow()
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun delayedRefreshAdoptsNewerLogin() = delayedRefresh(credentials("newer-login"))

    @Test
    fun delayedRefreshCannotUndoExternalLogout() = delayedRefresh(null)

    @Test
    fun delayedRefreshCannotOverwriteQueuedLogin() = queuedCredentialChange(credentials("new-login"))

    @Test
    fun delayedRefreshCannotOverwriteQueuedLogout() = queuedCredentialChange(null)

    private fun queuedCredentialChange(replacement: OAuthCredentials?) {
        val current = AtomicReference(Credentials("test", JsonSupport.json.encodeToString(credentials("expired", -60_000))))
        val queued = AtomicReference<Credentials>()
        val store = OAuthCredentialsStore(
            serviceName = "test", userName = "test",
            credentialReader = { current.get() },
            credentialWriter = { _, value -> queued.set(value) },
            coordinator = OAuthCredentialCoordinator(directory),
        )
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val service = service(store) {
            started.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
            credentials("old-login-rotated")
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val token = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.CLAUDE) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            if (replacement == null) store.clear() else store.save(replacement)
            val expectedWrite = queued.get()
            finish.countDown()
            assertNull(token.get(5, TimeUnit.SECONDS))
            assertEquals(expectedWrite, queued.get(), "refresh must not enqueue a write after a newer login/logout")
            current.set(queued.get())
            assertEquals(replacement?.accessToken, service.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
        } finally {
            finish.countDown()
            executor.shutdownNow()
            service.dispose()
        }
    }

    private fun delayedRefresh(replacement: OAuthCredentials?) {
        val store = Store(AtomicReference(credentials("expired", -60_000)))
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val service = service(store) {
            started.countDown()
            assertTrue(finish.await(5, TimeUnit.SECONDS))
            credentials("old-login-rotated")
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val token = executor.submit<String?> { service.getAccessTokenBlocking(QuotaProviderType.CLAUDE) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            store.current.set(replacement)
            finish.countDown()
            assertEquals(replacement?.accessToken, token.get(5, TimeUnit.SECONDS))
            assertEquals(replacement?.accessToken, store.current.get()?.accessToken)
        } finally {
            finish.countDown()
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun queuedPasswordSafeWriteDoesNotReplaySpentTokenInAnotherService() {
        val current = AtomicReference<OAuthCredentials?>(credentials("expired", -60_000))
        val calls = AtomicInteger()
        val queued = AtomicReference<OAuthCredentials>()
        val delayedStore = object : OAuthCredentialStore {
            override val coordinator = OAuthCredentialCoordinator(directory)
            override fun load(): OAuthCredentials? = current.get()
            override fun save(credentials: OAuthCredentials) { queued.set(credentials) }
            override fun clear() { current.set(null) }
        }
        val first = service(delayedStore) {
            calls.incrementAndGet()
            credentials("rotated")
        }
        val second = service(Store(current, OAuthCredentialCoordinator(directory))) {
            calls.incrementAndGet()
            error("the spent token must not be sent while the write is queued")
        }
        try {
            assertEquals("rotated", first.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
            assertNull(second.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
            current.set(queued.get())
            assertEquals("rotated", second.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
            assertEquals(1, calls.get())
        } finally {
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun pendingMemoryOnlyRotationCannotOverwriteQueuedNewLogin() {
        val current = AtomicReference(Credentials("test", JsonSupport.json.encodeToString(credentials("expired", -60_000))))
        val queued = AtomicReference<Credentials>()
        var writesFail = true
        val store = OAuthCredentialsStore(
            serviceName = "test", userName = "test",
            credentialReader = { current.get() },
            credentialWriter = { _, value ->
                if (writesFail) throw IllegalStateException("Password Safe unavailable")
                queued.set(value)
            },
            coordinator = OAuthCredentialCoordinator(directory),
        )
        val service = service(store) { credentials("rotated") }
        try {
            assertEquals("rotated", service.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
            writesFail = false
            store.save(credentials("new-login"))
            val loginWrite = queued.get()
            assertNull(service.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
            assertEquals(loginWrite, queued.get())
            current.set(loginWrite)
            assertEquals("new-login", service.getAccessTokenBlocking(QuotaProviderType.CLAUDE))
        } finally {
            service.dispose()
        }
    }

    private fun service(store: OAuthCredentialStore, refresh: (OAuthCredentials) -> OAuthCredentials): QuotaAuthService =
        QuotaAuthService(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            credentialStoreFactory = { _, _ -> store },
            tokenOperationsFactory = { _, _ -> object : OAuthTokenOperations {
                override suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String, state: String?): OAuthCredentials =
                    error("No browser login in this test")

                override suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials = refresh(existing)
            } },
            browserOpener = {},
        )

    private fun credentials(id: String, expiresIn: Long = 60 * 60_000): OAuthCredentials = OAuthCredentials(
        accessToken = id,
        refreshToken = "$id-refresh",
        expiresAt = System.currentTimeMillis() + expiresIn,
    )

    private class Store(
        val current: AtomicReference<OAuthCredentials?>,
        override val coordinator: OAuthCredentialCoordinator = OAuthCredentialCoordinator(),
    ) : OAuthCredentialStore {
        override fun load(): OAuthCredentials? = current.get()
        override fun save(credentials: OAuthCredentials) { current.set(credentials) }
        override fun clear() { current.set(null) }
    }
}
