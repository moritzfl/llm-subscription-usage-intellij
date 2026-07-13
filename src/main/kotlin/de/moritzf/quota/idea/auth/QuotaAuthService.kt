package de.moritzf.quota.idea.auth

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.idea.auth.OAuthClientConfig
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthCredentialsStore
import de.moritzf.quota.idea.auth.OAuthLoginFlow
import de.moritzf.quota.idea.auth.OAuthTokenClient
import de.moritzf.quota.idea.auth.OAuthTokenRequestException
import de.moritzf.quota.idea.auth.OAuthTokenOperations
import de.moritzf.quota.idea.common.QuotaProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Coordinates OAuth login, credential storage, and token refresh for quota requests.
 */
@Service(Service.Level.APP)
class QuotaAuthService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val httpClient: HttpClient = createHttpClient(),
    private val tokenOperationsFactory: (QuotaProviderType, OAuthClientConfig) -> OAuthTokenOperations = { _, config ->
        OAuthTokenClient(httpClient, config)
    },
    private val credentialStoreFactory: (QuotaProviderType) -> OAuthCredentialStore = { type ->
        OAuthCredentialsStore.forProvider(type)
    },
) : Disposable {
    private val providerStates = ConcurrentHashMap<QuotaProviderType, ProviderAuthState>()

    private fun stateFor(type: QuotaProviderType): ProviderAuthState {
        return providerStates.computeIfAbsent(type) { ProviderAuthState(type) }
    }

    private inner class ProviderAuthState(val type: QuotaProviderType) {
        val config = OAuthClientConfig.forProvider(type)
        val tokenOperations: OAuthTokenOperations = tokenOperationsFactory(type, config)
        val credentialStore: OAuthCredentialStore = credentialStoreFactory(type)
        
        val credentialsLock = Any()
        val refreshLock = Any()
        val cachedCredentials = AtomicReference<OAuthCredentials?>()
        val cacheLoading = AtomicBoolean(false)
        val cacheLoaded = AtomicBoolean(false)
        val authInProgress = AtomicBoolean(false)
        val pendingFlow = AtomicReference<OAuthLoginFlow?>()
        val credentialClearCounter = AtomicLong(0)
    }

    init {
        refreshCacheAsync(QuotaProviderType.OPEN_AI)
        refreshCacheAsync(QuotaProviderType.SUPERGROK)
        refreshCacheAsync(QuotaProviderType.CLAUDE)
    }

    fun startLoginFlow(type: QuotaProviderType, callback: (LoginResult) -> Unit, onAuthUrl: ((String) -> Unit)? = null) {
        val state = stateFor(type)
        if (!state.authInProgress.compareAndSet(false, true)) {
            LOG.warn("Login requested for ${type.displayName} while another login is already in progress")
            callback(LoginResult.error("Login already in progress"))
            return
        }

        scope.launch {
            val result = try {
                runLoginFlow(state, onAuthUrl)
            } catch (exception: Exception) {
                LOG.warn("Login flow failed for ${type.displayName}", exception)
                var message = exception.message
                if (message != null && message.lowercase().contains("address already in use")) {
                    message = "Port ${state.config.callbackPort} is already in use. Close the other app using it and try again."
                }
                LoginResult.error(message ?: "Login failed")
            } finally {
                state.authInProgress.set(false)
            }
            callback(result)
        }
    }

    fun isLoginInProgress(type: QuotaProviderType): Boolean = stateFor(type).authInProgress.get()

    /**
     * Completes a paste-based OAuth callback (Claude/Anthropic).
     * Returns null when the paste was accepted and token exchange is starting.
     * Returns an error message when the paste is invalid or no login is waiting.
     */
    fun completePastedCallback(type: QuotaProviderType, input: String): String? {
        val flow = stateFor(type).pendingFlow.get()
            ?: return "No login in progress. Click Log In first."
        return flow.completeWithPastedCallback(input)
    }

    fun abortLogin(type: QuotaProviderType, reason: String?): Boolean {
        val state = stateFor(type)
        val flow = state.pendingFlow.getAndSet(null) ?: return false
        state.authInProgress.set(false)
        val message = if (reason.isNullOrBlank()) "Login canceled" else reason
        flow.cancel(message)
        LOG.info("Login flow aborted for ${state.type.displayName}: $message")
        return true
    }

    fun clearCredentials(type: QuotaProviderType) {
        val state = stateFor(type)
        abortLogin(type, "Logged out")
        synchronized(state.credentialsLock) {
            state.credentialClearCounter.incrementAndGet()
            state.cachedCredentials.set(null)
            state.cacheLoaded.set(true)
            state.credentialStore.clear()
        }
        LOG.info("Cleared stored OAuth credentials for ${state.type.displayName}")
    }

    fun isLoggedIn(type: QuotaProviderType): Boolean {
        val credentials = cachedCredentialsOrScheduleLoad(stateFor(type))
        return credentials?.accessToken?.isNotBlank() == true
    }

    fun getAccessTokenBlocking(type: QuotaProviderType = QuotaProviderType.OPEN_AI): String? {
        val state = stateFor(type)
        var credentials = getCredentialsBlocking(state) ?: return null
        if (isExpired(credentials)) {
            credentials = refreshCredentialsBlocking(state) ?: return null
        }
        return credentials.accessToken
    }

    /**
     * Forces a token refresh after an upstream rejected the current access token with 401,
     * even if it is not yet locally expired. [staleAccessToken] is the token that was
     * rejected; if another thread already rotated past it, this is a no-op so concurrent
     * 401s do not trigger duplicate refreshes (which could invalidate a rotating refresh
     * token). Returns the access token in effect afterwards, or null if refresh failed.
     */
    fun forceRefreshBlocking(
        type: QuotaProviderType = QuotaProviderType.OPEN_AI,
        staleAccessToken: String?,
    ): String? {
        val state = stateFor(type)
        synchronized(state.refreshLock) {
            val latestCredentials = getCredentialsBlocking(state) ?: return null
            if (!staleAccessToken.isNullOrBlank() && latestCredentials.accessToken != staleAccessToken) {
                // Another request already refreshed past the rejected token.
                return latestCredentials.accessToken
            }

            val clearMarker = currentCredentialClearMarker(state)
            return try {
                val refreshed = runBlocking {
                    state.tokenOperations.refreshCredentials(latestCredentials)
                }
                persistCredentialsIfCurrent(state, clearMarker, refreshed, "force-refresh")?.accessToken
            } catch (exception: OAuthTokenRequestException) {
                LOG.warn("Forced token refresh failed for ${state.type.displayName}", exception)
                if (exception.isTerminalAuthFailure()) {
                    clearCredentialsIfUnchanged(state, latestCredentials)
                }
                null
            } catch (exception: Exception) {
                LOG.warn("Forced token refresh failed for ${state.type.displayName}", exception)
                null
            }
        }
    }

    fun getAccountId(type: QuotaProviderType = QuotaProviderType.OPEN_AI): String? =
        cachedCredentialsOrScheduleLoad(stateFor(type))?.accountId

    fun getHd(type: QuotaProviderType = QuotaProviderType.OPEN_AI): String? = 
        cachedCredentialsOrScheduleLoad(stateFor(type))?.hd

    fun refreshCacheAsync(type: QuotaProviderType) {
        val state = stateFor(type)
        if (!state.cacheLoading.compareAndSet(false, true)) {
            return
        }

        scope.launch {
            try {
                getCredentialsBlocking(state)
            } finally {
                state.cacheLoading.set(false)
            }
        }
    }

    private suspend fun runLoginFlow(state: ProviderAuthState, onAuthUrl: ((String) -> Unit)? = null): LoginResult {
        LOG.info("Starting OAuth login flow for ${state.type.displayName}")
        val flow = OAuthLoginFlow.start(state.config)
        state.pendingFlow.set(flow)
        return try {
            if (flow.usesLocalCallbackServer) {
                val callbackError = pingCallbackEndpoint(state.config)
                if (callbackError != null) {
                    return LoginResult.error(callbackError)
                }
            }

            try {
                onAuthUrl?.invoke(flow.authorizationUrl)
            } catch (exception: Exception) {
                LOG.warn("Failed to publish authorization URL to UI", exception)
            }

            BrowserUtil.browse(flow.authorizationUrl)
            val callback = flow.waitForCallback()
            LOG.info("OAuth callback received for ${state.type.displayName}; success=${callback.error == null}")

            if (callback.error != null) {
                return LoginResult.error(callback.error)
            }
            if (callback.code.isNullOrBlank()) {
                return LoginResult.error("No authorization code received")
            }

            val clearMarker = currentCredentialClearMarker(state)
            val credentials = state.tokenOperations.exchangeAuthorizationCode(
                callback.code,
                flow.codeVerifier,
                callback.state ?: flow.expectedState,
            )
            if (persistCredentialsIfCurrent(state, clearMarker, credentials, "login") == null) {
                return LoginResult.error("Login canceled")
            }
            LoginResult.success()
        } finally {
            state.pendingFlow.compareAndSet(flow, null)
            flow.stopServerNow()
        }
    }

    private suspend fun pingCallbackEndpoint(config: OAuthClientConfig): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:${config.callbackPort}/auth/ping"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val response = withContext(Dispatchers.IO) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
            if (response.statusCode() in 200..299) {
                null
            } else {
                "Callback test failed (HTTP ${response.statusCode()})"
            }
        } catch (exception: Exception) {
            LOG.warn("Callback endpoint ping failed", exception)
            val details = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            "Callback not reachable: $details"
        }
    }

    private fun cachedCredentialsOrScheduleLoad(state: ProviderAuthState): OAuthCredentials? {
        state.cachedCredentials.get()?.let { return it }
        if (!state.cacheLoaded.get()) {
            refreshCacheAsync(state.type)
        }
        return null
    }

    private fun getCredentialsBlocking(state: ProviderAuthState): OAuthCredentials? {
        val clearMarker = currentCredentialClearMarker(state)
        val credentials = state.credentialStore.load()
        synchronized(state.credentialsLock) {
            state.cacheLoaded.set(true)
            if (state.credentialClearCounter.get() != clearMarker) {
                state.cachedCredentials.set(null)
                return null
            }
            state.cachedCredentials.set(credentials)
            return credentials
        }
    }

    private fun refreshCredentialsBlocking(state: ProviderAuthState): OAuthCredentials? {
        synchronized(state.refreshLock) {
            val latestCredentials = getCredentialsBlocking(state) ?: return null
            if (!isExpired(latestCredentials)) {
                return latestCredentials
            }

            val clearMarker = currentCredentialClearMarker(state)
            return try {
                val refreshed = runBlocking {
                    state.tokenOperations.refreshCredentials(latestCredentials)
                }
                persistCredentialsIfCurrent(state, clearMarker, refreshed, "refresh")
            } catch (exception: OAuthTokenRequestException) {
                LOG.warn("Token refresh failed for ${state.type.displayName}", exception)
                if (exception.isTerminalAuthFailure()) {
                    clearCredentialsIfUnchanged(state, latestCredentials)
                }
                null
            } catch (exception: Exception) {
                LOG.warn("Token refresh failed for ${state.type.displayName}", exception)
                null
            }
        }
    }

    private fun currentCredentialClearMarker(state: ProviderAuthState): Long {
        return synchronized(state.credentialsLock) {
            state.credentialClearCounter.get()
        }
    }

    private fun persistCredentialsIfCurrent(
        state: ProviderAuthState,
        clearMarker: Long,
        credentials: OAuthCredentials,
        operation: String,
    ): OAuthCredentials? {
        synchronized(state.credentialsLock) {
            if (state.credentialClearCounter.get() != clearMarker) {
                state.cachedCredentials.set(null)
                state.cacheLoaded.set(true)
                LOG.info("Discarded OAuth credentials for ${state.type.displayName} from $operation after logout")
                return null
            }
            state.credentialStore.save(credentials)
            state.cachedCredentials.set(credentials)
            state.cacheLoaded.set(true)
            return credentials
        }
    }

    private fun clearCredentialsIfUnchanged(state: ProviderAuthState, expected: OAuthCredentials) {
        synchronized(state.credentialsLock) {
            if (!sameCredentials(state.cachedCredentials.get(), expected)) {
                LOG.info("Skipped clearing OAuth credentials for ${state.type.displayName} after refresh failure because credentials changed")
                return
            }
            state.credentialClearCounter.incrementAndGet()
            state.cachedCredentials.set(null)
            state.cacheLoaded.set(true)
            state.credentialStore.clear()
        }
        LOG.info("Cleared stored OAuth credentials for ${state.type.displayName} after refresh failure")
    }

    override fun dispose() {
        scope.cancel()
    }


    companion object {
        private val LOG = Logger.getInstance(QuotaAuthService::class.java)
        private const val EXPIRY_SKEW_MS: Long = 5 * 60 * 1000L

        @JvmStatic
        fun getInstance(): QuotaAuthService {
            return ApplicationManager.getApplication().getService(QuotaAuthService::class.java)
        }

        @JvmStatic
        fun parseQuery(query: String): Map<String, String> = OAuthLoginFlow.parseQuery(query)

        @JvmStatic
        fun parseUri(type: QuotaProviderType, value: String): URI {
            return OAuthLoginFlow.parseUri(value, OAuthClientConfig.forProvider(type).redirectUri)
        }

        private fun isExpired(credentials: OAuthCredentials): Boolean {
            return System.currentTimeMillis() >= credentials.expiresAt - EXPIRY_SKEW_MS
        }

        private fun sameCredentials(left: OAuthCredentials?, right: OAuthCredentials?): Boolean {
            if (left == null || right == null) {
                return left == right
            }
            return left.accessToken == right.accessToken &&
                left.refreshToken == right.refreshToken &&
                left.expiresAt == right.expiresAt &&
                left.accountId == right.accountId
        }

        private fun createHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}
