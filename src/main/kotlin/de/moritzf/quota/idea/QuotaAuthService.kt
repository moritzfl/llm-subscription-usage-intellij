package de.moritzf.quota.idea

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.idea.auth.OAuthClientConfig
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthCredentialsStore
import de.moritzf.quota.idea.auth.OAuthLoginFlow
import de.moritzf.quota.idea.auth.OAuthTokenClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

/**
 * Coordinates OAuth login, credential storage, and token refresh for quota requests.
 */
@Service(Service.Level.APP)
class QuotaAuthService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }
    private val tokenClient = OAuthTokenClient(httpClient, OAUTH_CONFIG)
    private val credentialsStore = OAuthCredentialsStore(SERVICE_NAME, USER_NAME)
    private val cachedCredentials = AtomicReference<OAuthCredentials?>()
    private val cacheLoading = AtomicBoolean(false)
    private val authInProgress = AtomicBoolean(false)
    private val pendingFlow = AtomicReference<OAuthLoginFlow?>()
    private val credentialClearCounter = AtomicLong(0)

    init {
        refreshCacheAsync()
    }

    fun startLoginFlow(callback: (LoginResult) -> Unit) {
        if (!authInProgress.compareAndSet(false, true)) {
            LOG.warn("Login requested while another login is already in progress")
            callback(LoginResult.error("Login already in progress"))
            return
        }

        scope.launch {
            val result = try {
                runLoginFlow()
            } catch (exception: Exception) {
                LOG.warn("Login flow failed", exception)
                var message = exception.message
                if (message != null && message.lowercase().contains("address already in use")) {
                    message = "Port ${OAUTH_CONFIG.callbackPort} is already in use. Close the other app using it and try again."
                }
                LoginResult.error(message ?: "Login failed")
            } finally {
                authInProgress.set(false)
            }
            callback(result)
        }
    }

    fun isLoginInProgress(): Boolean = authInProgress.get()

    fun abortLogin(reason: String?): Boolean {
        val flow = pendingFlow.getAndSet(null) ?: return false
        authInProgress.set(false)
        val message = if (reason.isNullOrBlank()) "Login canceled" else reason
        flow.cancel(message)
        LOG.info("Login flow aborted: $message")
        return true
    }

    fun clearCredentials() {
        credentialClearCounter.incrementAndGet()
        abortLogin("Logged out")
        cachedCredentials.set(null)
        credentialsStore.clear()
        LOG.info("Cleared stored OAuth credentials")
    }

    fun isLoggedIn(): Boolean {
        val credentials = cachedCredentials.get()
        if (credentials == null && !cacheLoading.get()) {
            refreshCacheAsync()
        }
        return credentials?.accessToken?.isNotBlank() == true
    }

    fun getAccessTokenBlocking(): String? {
        var credentials = getCredentialsBlocking() ?: return null
        if (isExpired(credentials)) {
            credentials = refreshCredentialsBlocking(credentials) ?: return null
        }
        return credentials.accessToken
    }

    fun getAccountId(): String? = cachedCredentials.get()?.accountId

    fun refreshCacheAsync() {
        if (!cacheLoading.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            try {
                getCredentialsBlocking()
            } finally {
                cacheLoading.set(false)
            }
        }
    }

    private suspend fun runLoginFlow(): LoginResult {
        LOG.info("Starting OAuth login flow")
        val flow = OAuthLoginFlow.start(OAUTH_CONFIG)
        pendingFlow.set(flow)

        val callbackError = pingCallbackEndpoint()
        if (callbackError != null) {
            pendingFlow.compareAndSet(flow, null)
            flow.stopServerNow()
            return LoginResult.error(callbackError)
        }

        openAuthorizationUi(flow.authorizationUrl)
        val callback = flow.waitForCallback()
        pendingFlow.compareAndSet(flow, null)
        LOG.info("OAuth callback received; success=${callback.error == null}")

        if (callback.error != null) {
            return LoginResult.error(callback.error)
        }
        if (callback.code.isNullOrBlank()) {
            return LoginResult.error("No authorization code received")
        }

        val credentials = tokenClient.exchangeAuthorizationCode(callback.code, flow.codeVerifier)
        saveCredentials(credentials)
        cachedCredentials.set(credentials)
        return LoginResult.success()
    }

    private suspend fun pingCallbackEndpoint(): String? {
        return try {
            val response = httpClient.get("http://localhost:${OAUTH_CONFIG.callbackPort}/auth/ping")
            if (response.status.value in 200..299) {
                null
            } else {
                "Callback test failed (HTTP ${response.status.value})"
            }
        } catch (exception: Exception) {
            "Callback not reachable: ${exception::class.java.simpleName}"
        }
    }

    private fun openAuthorizationUi(url: String) {
        LOG.info("Opening authorization UI: $url")
        BrowserUtil.browse(url)
    }

    private fun getCredentialsBlocking(): OAuthCredentials? {
        val clearMarker = credentialClearCounter.get()
        val credentials = credentialsStore.load()
        if (credentialClearCounter.get() != clearMarker) {
            cachedCredentials.set(null)
            return null
        }
        cachedCredentials.set(credentials)
        return credentials
    }

    private fun saveCredentials(credentials: OAuthCredentials) {
        credentialsStore.save(credentials)
    }

    private fun refreshCredentialsBlocking(existing: OAuthCredentials): OAuthCredentials? {
        return try {
            runBlocking {
                tokenClient.refreshCredentials(existing)
            }.also { refreshed ->
                saveCredentials(refreshed)
                cachedCredentials.set(refreshed)
            }
        } catch (exception: Exception) {
            LOG.warn("Token refresh failed", exception)
            clearCredentials()
            null
        }
    }

    fun dispose() {
        scope.cancel()
        httpClient.close()
    }

    class LoginResult private constructor(@JvmField val success: Boolean, @JvmField val message: String?) {
        companion object {
            @JvmStatic
            fun success(): LoginResult = LoginResult(true, null)

            @JvmStatic
            fun error(message: String): LoginResult = LoginResult(false, message)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(QuotaAuthService::class.java)
        private const val SERVICE_NAME = "OpenAI Usage Quota OAuth"
        private const val USER_NAME = "openai-oauth"
        private val OAUTH_CONFIG = OAuthClientConfig.openAiUsageQuotaDefaults()

        @JvmStatic
        fun getInstance(): QuotaAuthService {
            return ApplicationManager.getApplication().getService(QuotaAuthService::class.java)
        }

        @JvmStatic
        fun parseQuery(query: String): Map<String, String> = OAuthLoginFlow.parseQuery(query)

        @JvmStatic
        fun parseUri(value: String): URI = OAuthLoginFlow.parseUri(value, OAUTH_CONFIG.redirectUri)

        private fun isExpired(credentials: OAuthCredentials): Boolean {
            return System.currentTimeMillis() >= credentials.expiresAt - 5.minutes.inWholeMilliseconds
        }
    }
}
