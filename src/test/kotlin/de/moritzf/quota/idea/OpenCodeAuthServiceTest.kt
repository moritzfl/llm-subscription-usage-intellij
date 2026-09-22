package de.moritzf.quota.idea

import de.moritzf.quota.idea.auth.LoginResult
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.opencode.OpenCodeDeviceAuthorization
import de.moritzf.quota.opencode.OpenCodeDeviceTokenResult
import de.moritzf.quota.opencode.OpenCodeOAuthClient
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import de.moritzf.quota.opencode.OpenCodeWorkspace
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenCodeAuthServiceTest {
    @Test
    fun concurrentExpiryAndRejectedTokenRefreshesAreSingleFlight() {
        for (expired in listOf(false, true)) {
            val store = Store(valid().apply { if (expired) expiresAt = 0 })
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val calls = AtomicInteger()
            val client = object : OpenCodeOAuthClient() {
                override fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
                    calls.incrementAndGet()
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    return valid("rotated")
                }
            }
            val service = OpenCodeAuthService(oauthClient = client, credentialStoreFactory = { store })
            val executor = Executors.newFixedThreadPool(2)
            try {
                val rejected = if (expired) null else "access"
                val first = executor.submit<OAuthCredentials?> { service.credentials("a", rejected) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val second = executor.submit<OAuthCredentials?> { service.credentials("a", rejected) }
                release.countDown()
                assertEquals("rotated", first.get(5, TimeUnit.SECONDS)?.accessToken)
                assertSame(first.get(), second.get(5, TimeUnit.SECONDS))
                assertEquals(1, calls.get())
                assertEquals("rotated-refresh", store.value?.refreshToken)
            } finally {
                release.countDown()
                executor.shutdownNow()
                service.dispose()
            }
        }
    }

    @Test
    fun logoutDuringRefreshCannotRestoreCredentials() {
        val store = Store(valid().apply { expiresAt = 0 })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = OpenCodeAuthService(oauthClient = object : OpenCodeOAuthClient() {
            override fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return valid("rotated")
            }
        }, credentialStoreFactory = { store })
        val executor = Executors.newSingleThreadExecutor()
        try {
            val refresh = executor.submit<OAuthCredentials?> { service.credentials("a") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.clearCredentials("a")
            release.countDown()
            assertNull(refresh.get(5, TimeUnit.SECONDS))
            assertNull(store.value)
        } finally {
            release.countDown()
            executor.shutdownNow()
            service.dispose()
        }
    }

    @Test
    fun canceledLoginCannotOverwriteNewLoginOrDeliverStaleCallback() {
        val store = Store(null)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val polls = AtomicInteger()
        val oldCallback = AtomicInteger()
        val complete = CountDownLatch(1)
        val result = AtomicReference<LoginResult>()
        val service = OpenCodeAuthService(oauthClient = object : OpenCodeOAuthClient() {
            override fun requestDeviceAuthorization() = OpenCodeDeviceAuthorization("device", "CODE", "/console/device", 30, 1)
            override fun pollDeviceToken(deviceCode: String): OpenCodeDeviceTokenResult {
                val call = polls.incrementAndGet()
                if (call == 1) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
                return OpenCodeDeviceTokenResult.Authorized(valid(if (call == 1) "stale" else "new"))
            }
        }, credentialStoreFactory = { store }, browserOpener = {})
        try {
            service.startLoginFlow("a", { oldCallback.incrementAndGet() }, { _, _ -> })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.abortLogin("a")
            service.startLoginFlow("a", { result.set(it); complete.countDown() }, { _, _ -> })
            release.countDown()
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertTrue(result.get().success)
            assertEquals("new", store.value?.accessToken)
            assertEquals(0, oldCallback.get())
            assertFalse(service.isLoginInProgress("a"))
        } finally {
            release.countDown()
            service.dispose()
        }
    }

    @Test
    fun refreshIsPersistedBeforeFailedDiscoveryAndAccountScopesStayIsolated() {
        val first = Store(valid().apply { expiresAt = 0; accountId = "org_a" })
        val second = Store(valid("other").apply { accountId = "org_b" })
        val service = OpenCodeAuthService(oauthClient = object : OpenCodeOAuthClient() {
            override fun refreshCredentials(existing: OAuthCredentials) = valid("rotated").apply { accountId = existing.accountId }
        }, credentialStoreFactory = { if (it == "a") first else second })
        try {
            assertFailsWith<OpenCodeQuotaException> {
                service.workspaces("a", object : OpenCodeQuotaClient() {
                    override fun fetchWorkspaces(accessToken: String): List<OpenCodeWorkspace> {
                        assertEquals("rotated-refresh", first.value?.refreshToken)
                        throw OpenCodeQuotaException("Unavailable", 503)
                    }
                })
            }
            val orgs = service.workspaces("b", object : OpenCodeQuotaClient() {
                override fun fetchWorkspaces(accessToken: String) = listOf(OpenCodeWorkspace("org_a"), OpenCodeWorkspace("org_b"))
            })
            assertEquals(listOf("org_b"), orgs.map { it.id })
            service.clearCredentials("a")
            assertEquals("other", service.credentials("b")?.accessToken)
        } finally {
            service.dispose()
        }
    }

    private class Store(@Volatile var value: OAuthCredentials?) : OAuthCredentialStore {
        override val coordinator = de.moritzf.quota.idea.auth.OAuthCredentialCoordinator()
        override fun load() = value
        override fun save(credentials: OAuthCredentials) { value = credentials }
        override fun clear() { value = null }
    }

    private fun valid(token: String = "access") = OAuthCredentials(token, "$token-refresh", System.currentTimeMillis() + 600_000, "org_test")
}
