package de.moritzf.quota.idea.auth

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.auth.OAuthCredentials
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaHeadlessLoginTest {
    @Test
    fun deviceLoginPollsWithoutBrowserAndKeepsAccountCredentialsIsolated() = runTest {
        val client = DeviceClient()
        val fixture = Fixture(client, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            val completed = CompletableFuture<LoginResult>()
            fixture.service.startDeviceLoginFlow("second", OPEN_AI, { completed.complete(it) })
            runCurrent()
            assertEquals("CODE", fixture.service.deviceLoginPrompt("second", OPEN_AI)?.userCode)
            assertNull(fixture.service.deviceLoginPrompt(OPEN_AI.id, OPEN_AI))
            assertTrue(fixture.service.isLoginInProgress("second", OPEN_AI))
            advanceUntilIdle()
            assertTrue(assertNotNull(completed.getNow(null)).success)
            assertEquals("device-access", fixture.store("second").load()?.accessToken)
            assertNull(fixture.store(OPEN_AI.id).load())
            assertNull(fixture.service.deviceLoginPrompt("second", OPEN_AI))
            assertFalse(fixture.service.isLoginInProgress("second", OPEN_AI))
        } finally {
            fixture.service.dispose()
        }
    }

    @Test
    fun fallbackModesCannotReplaceAnotherActiveLogin() {
        val fixture =
            Fixture(
                object : DeviceClient() {
                    override suspend fun poll(
                        authorization: OAuthDeviceAuthorization
                    ): OAuthDevicePollResult = awaitCancellation()
                }
            )
        try {
            fixture.service.startDeviceLoginFlow("a", OPEN_AI, {})
            val token = CompletableFuture<LoginResult>()
            fixture.service.startPersonalTokenLogin("a", "at-token") { token.complete(it) }
            assertFalse(token.get(5, TimeUnit.SECONDS).success)
            val otherAccount = CompletableFuture<LoginResult>()
            fixture.service.startDeviceLoginFlow("b", OPEN_AI, { otherAccount.complete(it) })
            assertTrue(otherAccount.get(5, TimeUnit.SECONDS).message!!.contains("other"))
            assertEquals("CODE", fixture.service.deviceLoginPrompt("a", OPEN_AI)?.userCode)
            assertNull(fixture.store("a").load())
            assertNull(fixture.store("b").load())
        } finally {
            fixture.service.dispose()
        }
    }

    @Test
    fun pendingAndSlowDownRespectPollingIntervals() = runTest {
        val polls = mutableListOf<Long>()
        val fixture =
            Fixture(
                object : DeviceClient() {
                    override suspend fun poll(
                        authorization: OAuthDeviceAuthorization
                    ): OAuthDevicePollResult {
                        polls += testScheduler.currentTime
                        return when (polls.size) {
                            1 -> OAuthDevicePollResult.Pending
                            2 -> OAuthDevicePollResult.SlowDown
                            else -> OAuthDevicePollResult.Authorized(oauth())
                        }
                    }
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        try {
            val completed = CompletableFuture<LoginResult>()
            fixture.service.startDeviceLoginFlow(
                "a",
                QuotaProviderType.SUPERGROK,
                { completed.complete(it) },
            )
            advanceUntilIdle()
            assertTrue(assertNotNull(completed.getNow(null)).success)
            assertEquals(listOf(1_000L, 2_000L, 8_000L), polls)
        } finally {
            fixture.service.dispose()
        }
    }

    @Test
    fun deviceExpiryClearsPromptAndDoesNotReplaceExistingCredentials() = runTest {
        val polls = AtomicInteger()
        val fixture =
            Fixture(
                object : DeviceClient() {
                    override suspend fun requestAuthorization() =
                        authorization(expiresAt = System.currentTimeMillis() + 100)

                    override suspend fun poll(
                        authorization: OAuthDeviceAuthorization
                    ): OAuthDevicePollResult {
                        polls.incrementAndGet()
                        return super.poll(authorization)
                    }
                },
                dispatcher = StandardTestDispatcher(testScheduler),
            )
        try {
            fixture.store("a").save(oauth("existing"))
            val completed = CompletableFuture<LoginResult>()
            fixture.service.startDeviceLoginFlow("a", OPEN_AI, { completed.complete(it) })
            advanceUntilIdle()
            val result = assertNotNull(completed.getNow(null))
            assertFalse(result.success)
            assertTrue(result.message!!.contains("expired"))
            assertEquals(0, polls.get())
            assertNull(fixture.service.deviceLoginPrompt("a", OPEN_AI))
            assertFalse(fixture.service.isLoginInProgress("a", OPEN_AI))
            assertEquals("existing", fixture.store("a").load()?.accessToken)
        } finally {
            fixture.service.dispose()
        }
    }

    @Test
    fun canceledAuthorizationRequestCannotPublishPromptOverReplacementLogin() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staleCallbacks = AtomicInteger()
        val prompts = AtomicInteger()
        val fixture =
            Fixture(
                object : DeviceClient() {
                    override suspend fun requestAuthorization(): OAuthDeviceAuthorization =
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            authorization()
                        }
                }
            )
        try {
            fixture.service.startDeviceLoginFlow(
                "a",
                OPEN_AI,
                { staleCallbacks.incrementAndGet() },
                { prompts.incrementAndGet() },
            )
            withTimeout(5_000) { entered.await() }
            assertTrue(fixture.service.abortLogin("a", OPEN_AI, "Canceled"))
            val completed = CompletableFuture<LoginResult>()
            fixture.service.startPersonalTokenLogin("a", "at-new") { completed.complete(it) }
            assertTrue(completed.get(5, TimeUnit.SECONDS).success)
            release.complete(Unit)
            assertEquals(0, staleCallbacks.get())
            assertEquals(0, prompts.get())
            assertEquals("at-new", fixture.store("a").load()?.accessToken)
        } finally {
            release.complete(Unit)
            fixture.service.dispose()
        }
    }

    @Test
    fun logoutDuringPollCannotRestoreCredentialsOrDeliverStaleCallback() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        val fixture =
            Fixture(
                object : DeviceClient() {
                    override suspend fun poll(
                        authorization: OAuthDeviceAuthorization
                    ): OAuthDevicePollResult =
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                            OAuthDevicePollResult.Authorized(oauth("stale"))
                        }
                }
            )
        try {
            fixture.service.startDeviceLoginFlow("a", OPEN_AI, { callbacks.incrementAndGet() })
            withTimeout(5_000) { entered.await() }
            assertTrue(fixture.service.clearCredentials("a", OPEN_AI))
            release.complete(Unit)
            assertNull(fixture.store("a").load())
            assertEquals(0, callbacks.get())
            assertNull(fixture.service.deviceLoginPrompt("a", OPEN_AI))
            assertFalse(fixture.service.isLoginInProgress("a", OPEN_AI))
        } finally {
            release.complete(Unit)
            fixture.service.dispose()
        }
    }

    @Test
    fun canceledTokenValidationCannotOverwriteReplacementLogin() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val callbacks = AtomicInteger()
        val fixture =
            Fixture(
                validator = { input ->
                    if (input == "at-old")
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                    personal(input)
                }
            )
        try {
            fixture.service.startPersonalTokenLogin("a", "at-old") { callbacks.incrementAndGet() }
            withTimeout(5_000) { entered.await() }
            fixture.service.abortLogin("a", OPEN_AI, "Canceled")
            val complete = CompletableFuture<LoginResult>()
            fixture.service.startPersonalTokenLogin("a", "at-new") { complete.complete(it) }
            assertTrue(complete.get(5, TimeUnit.SECONDS).success)
            release.complete(Unit)
            assertEquals("at-new", fixture.store("a").load()?.accessToken)
            assertEquals(0, callbacks.get())
        } finally {
            release.complete(Unit)
            fixture.service.dispose()
        }
    }

    @Test
    fun personalTokenRejectionRequiresReplacementWithoutOauthRefreshOrDeletingToken() {
        val fixture = Fixture()
        try {
            val complete = CompletableFuture<LoginResult>()
            fixture.service.startPersonalTokenLogin("a", "at-current") { complete.complete(it) }
            assertTrue(complete.get(5, TimeUnit.SECONDS).success)
            assertTrue(fixture.store("a").load()!!.personalAccessToken)
            assertEquals("workspace", fixture.service.getAccountId("a", OPEN_AI))
            assertEquals("at-current", fixture.service.getAccessTokenBlocking("a", OPEN_AI))
            assertEquals(
                "at-current",
                fixture.service.forceRefreshBlocking("a", OPEN_AI, "at-previous"),
            )
            assertNull(fixture.service.forceRefreshBlocking("a", OPEN_AI, "at-current"))
            assertNull(fixture.service.getAccessTokenBlocking("a", OPEN_AI))
            assertEquals(
                OAuthConnectionState.RECONNECT_REQUIRED,
                fixture.service.connectionState("a", OPEN_AI),
            )
            assertEquals("at-current", fixture.store("a").load()?.accessToken)
            val replaced = CompletableFuture<LoginResult>()
            fixture.service.startPersonalTokenLogin("a", "at-replacement") { replaced.complete(it) }
            assertTrue(replaced.get(5, TimeUnit.SECONDS).success)
            assertEquals("at-replacement", fixture.service.getAccessTokenBlocking("a", OPEN_AI))
            assertEquals(
                OAuthConnectionState.CONNECTED,
                fixture.service.connectionState("a", OPEN_AI),
            )
        } finally {
            fixture.service.dispose()
        }
    }

    @Test
    fun failedTokenValidationAndPasswordSafeWriteRetainPriorLogin() {
        for (storageFailure in listOf(false, true)) {
            val fixture =
                Fixture(
                    validator = {
                        if (storageFailure) personal(it) else throw IOException("Invalid token")
                    }
                )
            try {
                fixture.store("a").save(oauth("existing"))
                fixture.store("a").failWrites = storageFailure
                val complete = CompletableFuture<LoginResult>()
                fixture.service.startPersonalTokenLogin("a", "at-invalid") { complete.complete(it) }
                assertFalse(complete.get(5, TimeUnit.SECONDS).success)
                assertEquals("existing", fixture.store("a").load()?.accessToken)
                assertFalse(fixture.service.isLoginInProgress("a", OPEN_AI))
            } finally {
                fixture.service.dispose()
            }
        }
    }

    private class Fixture(
        device: OAuthDeviceLoginOperations = DeviceClient(),
        validator: suspend (String) -> OAuthCredentials = { personal(it) },
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        private val stores = ConcurrentHashMap<String, Store>()

        fun store(id: String): Store = stores.computeIfAbsent(id) { Store() }

        val service =
            QuotaAuthService(
                scope = CoroutineScope(SupervisorJob() + dispatcher),
                credentialStoreFactory = { id, _ -> store(id) },
                tokenOperationsFactory = { _, _ ->
                    object : OAuthTokenOperations {
                        override suspend fun exchangeAuthorizationCode(
                            code: String,
                            codeVerifier: String,
                            state: String?,
                        ): OAuthCredentials = error("Unexpected browser login")

                        override suspend fun refreshCredentials(
                            existing: OAuthCredentials
                        ): OAuthCredentials = error("Personal tokens must not refresh")
                    }
                },
                browserOpener = { error("Headless login must not open a browser") },
                deviceLoginFactory = { _, _ -> device },
                personalTokenValidator = validator,
            )
    }

    private class Store : OAuthCredentialStore {
        override val coordinator = OAuthCredentialCoordinator()
        @Volatile private var json: String? = null
        var failWrites = false

        override fun load(): OAuthCredentials? = json?.let { JsonSupport.json.decodeFromString(it) }

        override fun save(credentials: OAuthCredentials) {
            if (failWrites) throw IOException("Password Safe unavailable")
            json = JsonSupport.json.encodeToString(credentials)
        }

        override fun clear() {
            json = null
        }
    }

    private open class DeviceClient : OAuthDeviceLoginOperations {
        override suspend fun requestAuthorization() = authorization()

        override suspend fun poll(authorization: OAuthDeviceAuthorization): OAuthDevicePollResult =
            OAuthDevicePollResult.Authorized(oauth())
    }

    companion object {
        private val OPEN_AI = QuotaProviderType.OPEN_AI

        private fun authorization(expiresAt: Long = System.currentTimeMillis() + 30_000) =
            OAuthDeviceAuthorization(
                DeviceLoginPrompt("https://auth.test/device", "CODE", expiresAt),
                "private-device",
                1,
            )

        private fun oauth(token: String = "device-access") =
            OAuthCredentials(token, "refresh", System.currentTimeMillis() + 600_000, "workspace")

        private fun personal(token: String) =
            OAuthCredentials(
                token,
                expiresAt = Long.MAX_VALUE,
                accountId = "workspace",
                personalAccessToken = true,
            )
    }
}
