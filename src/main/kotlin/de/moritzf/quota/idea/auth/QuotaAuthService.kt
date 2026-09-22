package de.moritzf.quota.idea.auth

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.idea.common.CredentialStorage
import de.moritzf.quota.idea.common.QuotaProviderType
import kotlinx.coroutines.CancellationException
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
    private val credentialStoreFactory: (String, QuotaProviderType) -> OAuthCredentialStore = { accountId, type ->
        OAuthCredentialsStore.forAccount(accountId, type)
    },
    private val browserOpener: (String) -> Unit = BrowserUtil::browse,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : Disposable {
    private val providerStates = ConcurrentHashMap<String, ProviderAuthState>()
    private val typeLoginLocks = ConcurrentHashMap<QuotaProviderType, Any>()

    private data class PendingCredentials(
        val credentials: OAuthCredentials,
        val expectedPersistedCredentials: OAuthCredentials,
        val clearMarker: Long,
        val expectedWriteVersion: String?,
    )

    private data class RefreshFailure(
        val credentials: OAuthCredentials,
        val failedAtMs: Long,
        val reconnectRequired: Boolean = false,
        val accessTokenUnusable: Boolean = false,
    )

    private fun stateFor(accountId: String, type: QuotaProviderType): ProviderAuthState {
        return providerStates.computeIfAbsent(accountId) { ProviderAuthState(accountId, type) }
    }

    private inner class ProviderAuthState(val accountId: String, val type: QuotaProviderType) {
        val config = OAuthClientConfig.forProvider(type)
        val tokenOperations: OAuthTokenOperations = tokenOperationsFactory(type, config)
        val credentialStore: OAuthCredentialStore = credentialStoreFactory(accountId, type)
        
        val credentialsLock = Any()
        val cachedCredentials = AtomicReference<OAuthCredentials?>()
        val cacheLoading = AtomicBoolean(false)
        val cacheLoaded = AtomicBoolean(false)
        val authInProgress = AtomicBoolean(false)
        val pendingFlow = AtomicReference<OAuthLoginFlow?>()
        val credentialClearCounter = AtomicLong(0)
        val loginGeneration = AtomicLong(0)
        val pendingCredentials = AtomicReference<PendingCredentials?>()
        val credentialLoadFailed = AtomicBoolean(false)
        val lastRefreshFailure = AtomicReference<RefreshFailure?>()
    }

    init {
        if (CredentialStorage.isMemoryOnly()) {
            LOG.warn("IDE credential storage is memory-only: ${CredentialStorage.MEMORY_ONLY_WARNING}")
        }
        refreshCacheAsync(QuotaProviderType.OPEN_AI)
        refreshCacheAsync(QuotaProviderType.SUPERGROK)
        refreshCacheAsync(QuotaProviderType.CLAUDE)
    }

    fun startLoginFlow(type: QuotaProviderType, callback: (LoginResult) -> Unit, onAuthUrl: ((String) -> Unit)? = null) {
        startLoginFlow(type.id, type, callback, onAuthUrl)
    }

    fun startLoginFlow(
        accountId: String,
        type: QuotaProviderType,
        callback: (LoginResult) -> Unit,
        onAuthUrl: ((String) -> Unit)? = null,
    ) {
        val typeLock = typeLoginLocks.computeIfAbsent(type) { Any() }
        val loginGeneration: Long?
        val state: ProviderAuthState
        synchronized(typeLock) {
            val existing = providerStates.values.firstOrNull { it.type == type && it.authInProgress.get() }
            if (existing != null && existing.accountId != accountId) {
                callback(LoginResult.error("Finish or cancel the other ${type.displayName} login first."))
                return
            }
            state = stateFor(accountId, type)
            loginGeneration = synchronized(state.credentialsLock) {
                if (!state.authInProgress.compareAndSet(false, true)) {
                    null
                } else {
                    state.loginGeneration.incrementAndGet()
                }
            }
        }
        if (loginGeneration == null) {
            LOG.warn("Login requested for ${type.displayName} while another login is already in progress")
            callback(LoginResult.error("Login already in progress"))
            return
        }

        scope.launch {
            var deliverResult: Boolean
            val result = try {
                runLoginFlow(state, loginGeneration, onAuthUrl)
            } catch (exception: Exception) {
                LOG.warn("Login flow failed for ${type.displayName}", exception)
                var message = exception.message
                if (message != null && message.lowercase().contains("address already in use")) {
                    message = "Port ${state.config.callbackPort} is already in use. Close the other app using it and try again."
                }
                LoginResult.error(message ?: "Login failed")
            } finally {
                deliverResult = synchronized(state.credentialsLock) {
                    if (state.loginGeneration.get() == loginGeneration) {
                        state.authInProgress.compareAndSet(true, false)
                        true
                    } else {
                        false
                    }
                }
            }
            if (deliverResult) {
                callback(result)
            }
        }
    }

    fun isLoginInProgress(type: QuotaProviderType): Boolean =
        providerStates.values.any { it.type == type && it.authInProgress.get() }

    fun isLoginInProgress(accountId: String, type: QuotaProviderType): Boolean =
        stateFor(accountId, type).authInProgress.get()

    /**
     * Completes a paste-based OAuth callback (Claude/Anthropic).
     * Returns null when the paste was accepted and token exchange is starting.
     * Returns an error message when the paste is invalid or no login is waiting.
     */
    fun completePastedCallback(type: QuotaProviderType, input: String): String? =
        completePastedCallback(type.id, type, input)

    fun completePastedCallback(accountId: String, type: QuotaProviderType, input: String): String? {
        val flow = stateFor(accountId, type).pendingFlow.get()
            ?: return "No login in progress. Click Log In first."
        return flow.completeWithPastedCallback(input)
    }

    fun abortLogin(type: QuotaProviderType, reason: String?): Boolean = abortLogin(type.id, type, reason)

    fun abortLogin(accountId: String, type: QuotaProviderType, reason: String?): Boolean {
        val state = stateFor(accountId, type)
        val flow = synchronized(state.credentialsLock) {
            if (!state.authInProgress.getAndSet(false)) {
                return false
            }
            state.loginGeneration.incrementAndGet()
            state.pendingFlow.getAndSet(null)
        }
        val message = if (reason.isNullOrBlank()) "Login canceled" else reason
        flow?.cancel(message)
        LOG.info("Login flow aborted for ${state.type.displayName}: $message")
        return true
    }

    fun clearCredentials(type: QuotaProviderType): Boolean = clearCredentials(type.id, type)

    fun clearCredentials(accountId: String, type: QuotaProviderType): Boolean {
        val state = stateFor(accountId, type)
        abortLogin(accountId, type, "Logged out")
        try {
            state.credentialStore.coordinator.withWriteLock {
                synchronized(state.credentialsLock) {
                    state.credentialStore.clear()
                    state.credentialClearCounter.incrementAndGet()
                    state.pendingCredentials.set(null)
                    state.lastRefreshFailure.set(null)
                    state.credentialLoadFailed.set(false)
                    state.cachedCredentials.set(null)
                    state.cacheLoaded.set(true)
                }
            }
        } catch (exception: Exception) {
            LOG.warn("Failed to clear stored OAuth credentials for ${state.type.displayName}", exception)
            return false
        }
        LOG.info("Cleared stored OAuth credentials for ${state.type.displayName}")
        return true
    }

    fun isLoggedIn(type: QuotaProviderType): Boolean = isLoggedIn(type.id, type)

    fun isLoggedIn(accountId: String, type: QuotaProviderType): Boolean {
        val state = stateFor(accountId, type)
        val credentials = cachedCredentialsOrScheduleLoad(state)
        return credentials?.accessToken?.isNotBlank() == true || state.credentialLoadFailed.get()
    }

    fun connectionState(type: QuotaProviderType): OAuthConnectionState = connectionState(type.id, type)

    fun connectionState(accountId: String, type: QuotaProviderType): OAuthConnectionState {
        val state = stateFor(accountId, type)
        val credentials = cachedCredentialsOrScheduleLoad(state)
        val failure = matchingFailure(state, credentials)
        return when {
            failure?.reconnectRequired == true -> OAuthConnectionState.RECONNECT_REQUIRED
            state.credentialLoadFailed.get() -> OAuthConnectionState.TEMPORARY_FAILURE
            credentials?.accessToken.isNullOrBlank() -> OAuthConnectionState.LOGGED_OUT
            failure != null -> OAuthConnectionState.TEMPORARY_FAILURE
            else -> OAuthConnectionState.CONNECTED
        }
    }

    /** A fresh token still rejected by the usage API, or missing consent, needs a new login. */
    fun requireReconnect(accountId: String, type: QuotaProviderType, rejectedAccessToken: String) {
        val state = stateFor(accountId, type)
        synchronized(state.credentialsLock) {
            val current = state.cachedCredentials.get() ?: return
            if (current.accessToken == rejectedAccessToken) {
                state.lastRefreshFailure.set(RefreshFailure(current, nowMs(), reconnectRequired = true, accessTokenUnusable = true))
            }
        }
    }

    fun getAccessTokenBlocking(type: QuotaProviderType = QuotaProviderType.OPEN_AI): String? =
        getAccessTokenBlocking(type.id, type)

    fun getAccessTokenBlocking(accountId: String, type: QuotaProviderType): String? {
        val state = stateFor(accountId, type)
        var credentials = getCredentialsBlocking(state) ?: return null
        if (matchingFailure(state, credentials)?.reconnectRequired == true) return null
        if (isExpired(credentials) || matchingFailure(state, credentials)?.accessTokenUnusable == true) {
            credentials = refreshCredentialsBlocking(state) ?: getCredentialsBlocking(state) ?: return null
        }
        val failure = matchingFailure(state, credentials)
        return credentials.accessToken?.takeIf {
            // Early refresh is best-effort. Never reuse an expired, rejected, or superseded token.
            nowMs() < credentials.expiresAt && failure?.reconnectRequired != true && failure?.accessTokenUnusable != true
        }
    }

    fun hasCredentialsBlocking(type: QuotaProviderType): Boolean = hasCredentialsBlocking(type.id, type)

    fun hasCredentialsBlocking(accountId: String, type: QuotaProviderType): Boolean {
        val state = stateFor(accountId, type)
        val credentials = getCredentialsBlocking(state)
        return credentials?.accessToken?.isNotBlank() == true || state.credentialLoadFailed.get()
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
    ): String? = forceRefreshBlocking(type.id, type, staleAccessToken)

    fun forceRefreshBlocking(
        accountId: String,
        type: QuotaProviderType,
        staleAccessToken: String?,
    ): String? {
        val state = stateFor(accountId, type)
        return withRefreshCoordination(state) {
            val clearMarker = currentCredentialClearMarker(state)
            val latestCredentials = getCredentialsBlocking(state) ?: return@withRefreshCoordination null
            val rejected = staleAccessToken.isNullOrBlank() || latestCredentials.accessToken == staleAccessToken
            if (!rejected && !isExpired(latestCredentials)) {
                // Another request already refreshed past the rejected token.
                return@withRefreshCoordination latestCredentials.accessToken
            }

            logRefreshAttempt(state, latestCredentials, "upstream rejected the access token")
            refreshWithFailureBackoff(state, clearMarker, latestCredentials, "force-refresh", rejected)
                ?.takeIf { nowMs() < it.expiresAt }?.accessToken
        }
    }

    fun getAccountId(type: QuotaProviderType = QuotaProviderType.OPEN_AI): String? =
        getAccountId(type.id, type)

    fun getAccountId(accountId: String, type: QuotaProviderType): String? =
        cachedCredentialsOrScheduleLoad(stateFor(accountId, type))?.accountId

    fun peekAccessToken(accountId: String, type: QuotaProviderType): String? {
        return stateFor(accountId, type).cachedCredentials.get()?.accessToken
    }

    fun forgetAccount(accountId: String) {
        providerStates.remove(accountId)
    }

    fun refreshCacheAsync(type: QuotaProviderType) {
        refreshCacheAsync(type.id, type)
    }

    fun refreshCacheAsync(accountId: String, type: QuotaProviderType) {
        val state = stateFor(accountId, type)
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

    private suspend fun runLoginFlow(
        state: ProviderAuthState,
        loginGeneration: Long,
        onAuthUrl: ((String) -> Unit)? = null,
    ): LoginResult {
        if (state.loginGeneration.get() != loginGeneration) {
            return LoginResult.error("Login canceled")
        }
        LOG.info("Starting OAuth login flow for ${state.type.displayName}")
        val flow = OAuthLoginFlow.start(state.config)
        val published = synchronized(state.credentialsLock) {
            state.loginGeneration.get() == loginGeneration && state.pendingFlow.compareAndSet(null, flow)
        }
        if (!published) {
            flow.cancel("Login canceled")
            flow.stopServerNow()
            return LoginResult.error("Login canceled")
        }
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

            browserOpener(flow.authorizationUrl)
            val callback = flow.waitForCallback()
            LOG.info("OAuth callback received for ${state.type.displayName}; success=${callback.error == null}")

            if (callback.error != null) {
                return LoginResult.error(callback.error)
            }
            if (callback.code.isNullOrBlank()) {
                return LoginResult.error("No authorization code received")
            }
            if (state.loginGeneration.get() != loginGeneration) {
                return LoginResult.error("Login canceled")
            }

            val clearMarker = currentCredentialClearMarker(state)
            val credentials = state.tokenOperations.exchangeAuthorizationCode(
                callback.code,
                flow.codeVerifier,
                callback.state ?: flow.expectedState,
            )
            if (persistCredentialsIfCurrent(
                    state = state,
                    clearMarker = clearMarker,
                    credentials = credentials,
                    operation = "login",
                    loginGeneration = loginGeneration,
                ) == null
            ) {
                return LoginResult.error("Login canceled")
            }
            state.lastRefreshFailure.set(null)
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
            refreshCacheAsync(state.accountId, state.type)
        }
        return null
    }

    private fun getCredentialsBlocking(state: ProviderAuthState): OAuthCredentials? {
        state.pendingCredentials.get()?.let { pending ->
            return resolvePendingCredentials(state, pending)
        }
        val clearMarker = currentCredentialClearMarker(state)
        val credentials = try {
            state.credentialStore.load()
        } catch (exception: Exception) {
            LOG.warn("Failed to load OAuth credentials for ${state.type.displayName}; keeping cached state", exception)
            return synchronized(state.credentialsLock) {
                if (state.credentialClearCounter.get() != clearMarker) {
                    null
                } else {
                    state.credentialLoadFailed.set(true)
                    state.cachedCredentials.get()
                }
            }
        }
        synchronized(state.credentialsLock) {
            state.cacheLoaded.set(true)
            if (state.credentialClearCounter.get() != clearMarker) {
                state.cachedCredentials.set(null)
                return null
            }
            state.credentialLoadFailed.set(false)
            logExternalCredentialChange(state, credentials)
            state.cachedCredentials.set(credentials)
            return credentials
        }
    }

    private fun resolvePendingCredentials(state: ProviderAuthState, pending: PendingCredentials): OAuthCredentials? {
        val persisted = try {
            state.credentialStore.load()
        } catch (exception: Exception) {
            LOG.warn(
                "Failed to reload OAuth credentials for ${state.type.displayName};" +
                    " keeping the rotated token in memory",
                exception,
            )
            return synchronized(state.credentialsLock) {
                if (state.pendingCredentials.get() === pending &&
                    state.credentialClearCounter.get() == pending.clearMarker
                ) {
                    state.cachedCredentials.set(pending.credentials)
                    pending.credentials
                } else {
                    state.pendingCredentials.get()?.credentials ?: state.cachedCredentials.get()
                }
            }
        }
        return state.credentialStore.coordinator.withWriteLock {
            synchronized(state.credentialsLock) {
                if (state.pendingCredentials.get() !== pending) {
                    return@withWriteLock state.pendingCredentials.get()?.credentials ?: state.cachedCredentials.get()
                }
                if (state.credentialClearCounter.get() != pending.clearMarker) {
                    state.pendingCredentials.set(null)
                    state.cachedCredentials.set(null)
                    state.cacheLoaded.set(true)
                    return@withWriteLock null
                }
                state.credentialLoadFailed.set(false)
                if (!sameCredentials(persisted, pending.expectedPersistedCredentials)) {
                    LOG.info("Discarded memory-only OAuth credentials for ${state.type.displayName} after stored credentials changed")
                    state.pendingCredentials.set(null)
                    state.cachedCredentials.set(persisted)
                    state.cacheLoaded.set(true)
                    return@withWriteLock persisted
                }
                try {
                    // The first load can have raced a login/logout in another IDE.
                    val latest = state.credentialStore.load()
                    if (state.credentialStore.coordinator.readWriteVersion() != pending.expectedWriteVersion) {
                        state.pendingCredentials.set(null)
                        state.cachedCredentials.set(latest?.takeUnless { sameCredentials(it, pending.expectedPersistedCredentials) })
                    } else if (!sameCredentials(latest, pending.expectedPersistedCredentials)) {
                        state.pendingCredentials.set(null)
                        state.cachedCredentials.set(latest)
                    } else {
                        state.credentialStore.save(pending.credentials)
                        state.pendingCredentials.set(null)
                        state.cachedCredentials.set(pending.credentials)
                    }
                } catch (exception: Exception) {
                    LOG.warn(
                        "Failed to persist memory-only OAuth credentials for ${state.type.displayName}; will retry later",
                        exception,
                    )
                    state.cachedCredentials.set(pending.credentials)
                }
                state.cacheLoaded.set(true)
                state.cachedCredentials.get()
            }
        }
    }

    /**
     * Reports when the persisted login differs from the copy this IDE last saw without this IDE
     * having written it. That only happens when another JetBrains IDE shares the credential store,
     * which is the situation where two refreshes race for the same rotating refresh token.
     */
    private fun logExternalCredentialChange(state: ProviderAuthState, loaded: OAuthCredentials?) {
        val cached = state.cachedCredentials.get() ?: return
        if (loaded == null) {
            LOG.info("Stored OAuth credentials for ${state.type.displayName} disappeared outside this IDE")
            return
        }
        if (loaded.refreshToken == cached.refreshToken) {
            return
        }
        LOG.info(
            "Stored OAuth credentials for ${state.type.displayName} changed outside this IDE:" +
                " refresh ${QuotaTokenUtil.fingerprint(cached.refreshToken)}" +
                " -> ${QuotaTokenUtil.fingerprint(loaded.refreshToken)}"
        )
    }

    private fun refreshCredentialsBlocking(state: ProviderAuthState): OAuthCredentials? {
        return withRefreshCoordination(state) {
            val clearMarker = currentCredentialClearMarker(state)
            val latestCredentials = getCredentialsBlocking(state) ?: return@withRefreshCoordination null
            if (!isExpired(latestCredentials) && matchingFailure(state, latestCredentials)?.accessTokenUnusable != true) {
                return@withRefreshCoordination latestCredentials
            }

            val reason = when {
                matchingFailure(state, latestCredentials)?.accessTokenUnusable == true -> "access token is no longer usable"
                nowMs() >= latestCredentials.expiresAt -> "access token expired"
                else -> "access token expires soon"
            }
            logRefreshAttempt(state, latestCredentials, reason)
            refreshWithFailureBackoff(state, clearMarker, latestCredentials, "refresh")
        }
    }

    private fun <T> withRefreshCoordination(state: ProviderAuthState, action: () -> T?): T? {
        return try {
            state.credentialStore.coordinator.withRefreshLock(action)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            LOG.warn("Could not coordinate token refresh for ${state.type.displayName}; will retry later", exception)
            state.cachedCredentials.get()?.let { credentials ->
                if (matchingFailure(state, credentials) == null) {
                    state.lastRefreshFailure.set(RefreshFailure(credentials, nowMs()))
                }
            }
            null
        }
    }

    /**
     * Refresh failures never delete stored credentials. A changed shared-store snapshot may be a
     * token another IDE already rotated; adopt it and retry only when it is still expired.
     */
    private fun refreshWithFailureBackoff(
        state: ProviderAuthState,
        clearMarker: Long,
        initialCredentials: OAuthCredentials,
        operation: String,
        accessTokenRejected: Boolean = false,
    ): OAuthCredentials? {
        val now = nowMs()
        val rejected = accessTokenRejected || matchingFailure(state, initialCredentials)?.accessTokenUnusable == true
        val receipt = state.credentialStore.coordinator.readReceipt()
        if (receipt != null && receipt.matches(initialCredentials) &&
            (receipt.outcome != OAuthRefreshReceipt.Outcome.TEMPORARY_FAILURE ||
                now - receipt.attemptedAtMs < REFRESH_FAILURE_BACKOFF_MS)
        ) {
            state.lastRefreshFailure.set(RefreshFailure(
                initialCredentials, receipt.attemptedAtMs,
                reconnectRequired = receipt.outcome == OAuthRefreshReceipt.Outcome.REJECTED,
                accessTokenUnusable = rejected || receipt.accessTokenRejected || receipt.outcome == OAuthRefreshReceipt.Outcome.ROTATED,
            ))
            LOG.info("Skipped token $operation for ${state.type.displayName} after a shared ${receipt.outcome} outcome")
            return null
        }
        val previousFailure = state.lastRefreshFailure.get()
        if (previousFailure != null &&
            sameCredentials(previousFailure.credentials, initialCredentials) &&
            (previousFailure.reconnectRequired || now - previousFailure.failedAtMs < REFRESH_FAILURE_BACKOFF_MS)
        ) {
            if (rejected && !previousFailure.accessTokenUnusable) {
                state.lastRefreshFailure.set(previousFailure.copy(accessTokenUnusable = true))
            }
            LOG.info("Skipped repeated token $operation for ${state.type.displayName} after a recent failure")
            return null
        }
        var attemptedCredentials = initialCredentials
        val refreshed = refreshWithStoreRecovery(state, clearMarker, initialCredentials, operation, rejected) {
            attemptedCredentials = it
        }
        if (refreshed == null) {
            if (currentCredentialClearMarker(state) == clearMarker) {
                val outcome = state.credentialStore.coordinator.readReceipt()?.takeIf { it.matches(attemptedCredentials) }
                state.lastRefreshFailure.set(
                    RefreshFailure(attemptedCredentials, nowMs(),
                        reconnectRequired = outcome?.outcome == OAuthRefreshReceipt.Outcome.REJECTED,
                        accessTokenUnusable = outcome?.outcome == OAuthRefreshReceipt.Outcome.ROTATED ||
                            (rejected && sameCredentials(attemptedCredentials, initialCredentials)),
                    )
                )
            }
        } else {
            state.lastRefreshFailure.set(null)
        }
        return refreshed
    }

    private fun refreshWithStoreRecovery(
        state: ProviderAuthState,
        clearMarker: Long,
        initialCredentials: OAuthCredentials,
        operation: String,
        accessTokenRejected: Boolean,
        onAttempt: (OAuthCredentials) -> Unit,
    ): OAuthCredentials? {
        var credentials = initialCredentials
        var attempt = 0
        while (attempt < 2) {
            attempt++
            val (current, writeVersion) = state.credentialStore.coordinator.withWriteLock {
                getCredentialsBlocking(state) to state.credentialStore.coordinator.readWriteVersion()
            }
            if (current == null || currentCredentialClearMarker(state) != clearMarker) return null
            if (!sameCredentials(current, credentials)) {
                credentials = current
                if (!isExpired(credentials)) return credentials
            }
            val receipt = OAuthRefreshReceipt(
                OAuthRefreshReceipt.fingerprint(credentials), OAuthRefreshReceipt.Outcome.TEMPORARY_FAILURE,
                nowMs(),
                accessTokenRejected = accessTokenRejected && sameCredentials(credentials, initialCredentials),
            )
            // Record before sending: a crashed/interrupted caller must also leave a backoff.
            state.credentialStore.coordinator.writeReceipt(receipt)
            try {
                onAttempt(credentials)
                val refreshedCredentials = runBlocking {
                    state.tokenOperations.refreshCredentials(credentials)
                }
                val persisted = persistCredentialsIfCurrent(
                    state = state,
                    clearMarker = clearMarker,
                    credentials = refreshedCredentials,
                    operation = operation,
                    previousCredentials = credentials,
                    keepInMemoryOnFailure = true,
                    expectedWriteVersion = writeVersion,
                )
                try {
                    state.credentialStore.coordinator.writeReceipt(receipt.copy(outcome = OAuthRefreshReceipt.Outcome.ROTATED))
                } catch (exception: Exception) {
                    LOG.warn("Could not record completed OAuth refresh for ${state.type.displayName}", exception)
                }
                return persisted
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: OAuthTokenRequestException) {
                LOG.warn("Token $operation failed for ${state.type.displayName} (attempt $attempt)", exception)
                if (!exception.isTerminalAuthFailure()) {
                    return null
                }
                state.credentialStore.coordinator.writeReceipt(receipt.copy(outcome = OAuthRefreshReceipt.Outcome.REJECTED))
                if (attempt == 1) {
                    if (currentCredentialClearMarker(state) != clearMarker) {
                        return null
                    }
                    val persisted = try {
                        state.credentialStore.load()
                    } catch (loadException: Exception) {
                        state.credentialLoadFailed.set(true)
                        LOG.warn("Failed to reload OAuth credentials for ${state.type.displayName} after invalid_grant", loadException)
                        return null
                    }
                    state.credentialLoadFailed.set(false)
                    if (persisted == null) {
                        synchronized(state.credentialsLock) {
                            if (state.credentialClearCounter.get() == clearMarker) {
                                state.pendingCredentials.set(null)
                                state.cachedCredentials.set(null)
                                state.cacheLoaded.set(true)
                            }
                        }
                        return null
                    }
                    if (currentCredentialClearMarker(state) != clearMarker) {
                        return null
                    }
                    if (sameCredentials(persisted, credentials)) {
                        return null
                    }
                    credentials = adoptCredentialsIfCurrent(state, clearMarker, persisted, operation)
                        ?: return null
                    if (!isExpired(credentials)) {
                        return credentials
                    }
                    continue
                }
                return null
            } catch (exception: Exception) {
                LOG.warn("Token $operation failed for ${state.type.displayName} (attempt $attempt)", exception)
                return null
            }
        }
        return null
    }

    private fun adoptCredentialsIfCurrent(
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
            state.cachedCredentials.set(credentials)
            state.cacheLoaded.set(true)
            return credentials
        }
    }

    private fun logRefreshAttempt(state: ProviderAuthState, credentials: OAuthCredentials, reason: String) {
        LOG.info(
            "Refreshing ${state.type.displayName} token because $reason" +
                " (refresh=${QuotaTokenUtil.fingerprint(credentials.refreshToken)}," +
                " expiredForMs=${nowMs() - credentials.expiresAt})"
        )
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
        previousCredentials: OAuthCredentials? = null,
        keepInMemoryOnFailure: Boolean = false,
        loginGeneration: Long? = null,
        expectedWriteVersion: String? = null,
    ): OAuthCredentials? {
        return state.credentialStore.coordinator.withWriteLock {
            synchronized(state.credentialsLock) {
                if (state.credentialClearCounter.get() != clearMarker) {
                    state.cachedCredentials.set(null)
                    state.cacheLoaded.set(true)
                    LOG.info("Discarded OAuth credentials for ${state.type.displayName} from $operation after logout")
                    return@withWriteLock null
                }
                if (loginGeneration != null && state.loginGeneration.get() != loginGeneration) {
                    LOG.info("Discarded OAuth credentials for ${state.type.displayName} from canceled login")
                    return@withWriteLock null
                }
                val existingPending = state.pendingCredentials.get()
                val expectedPersistedCredentials = if (existingPending != null &&
                    sameCredentials(existingPending.credentials, previousCredentials)
                ) existingPending.expectedPersistedCredentials else previousCredentials
                try {
                    if (previousCredentials != null) {
                        val persisted = state.credentialStore.load()
                        if (state.credentialStore.coordinator.readWriteVersion() != expectedWriteVersion) {
                            LOG.info("Discarded delayed OAuth $operation for ${state.type.displayName} after another credential write")
                            state.pendingCredentials.set(null)
                            // The other IDE's write may not yet be visible through Password Safe.
                            val current = persisted?.takeUnless { sameCredentials(it, expectedPersistedCredentials) }
                            state.cachedCredentials.set(current)
                            state.cacheLoaded.set(current != null)
                            return@withWriteLock current
                        }
                        if (!sameCredentials(persisted, expectedPersistedCredentials)) {
                            LOG.info("Discarded delayed OAuth $operation for ${state.type.displayName} after stored credentials changed")
                            state.pendingCredentials.set(null)
                            state.credentialLoadFailed.set(false)
                            state.cachedCredentials.set(persisted)
                            state.cacheLoaded.set(true)
                            return@withWriteLock persisted
                        }
                    }
                    state.credentialStore.save(credentials)
                    state.pendingCredentials.set(null)
                } catch (exception: Exception) {
                    if (!keepInMemoryOnFailure) {
                        throw exception
                    }
                    state.pendingCredentials.set(
                        PendingCredentials(
                            credentials = credentials,
                            expectedPersistedCredentials = requireNotNull(expectedPersistedCredentials),
                            clearMarker = clearMarker,
                            expectedWriteVersion = expectedWriteVersion,
                        )
                    )
                    LOG.warn(
                        "Failed to persist OAuth credentials for ${state.type.displayName} after $operation;" +
                            " keeping them authoritative in memory and retrying persistence later",
                        exception,
                    )
                }
                state.credentialLoadFailed.set(false)
                state.cachedCredentials.set(credentials)
                state.cacheLoaded.set(true)
                credentials
            }
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private fun isExpired(credentials: OAuthCredentials): Boolean = nowMs() >= credentials.expiresAt - EXPIRY_SKEW_MS

    private fun matchingFailure(state: ProviderAuthState, credentials: OAuthCredentials?): RefreshFailure? =
        state.lastRefreshFailure.get()?.takeIf { sameCredentials(it.credentials, credentials) }

    companion object {
        private val LOG = Logger.getInstance(QuotaAuthService::class.java)
        private const val EXPIRY_SKEW_MS: Long = 5 * 60 * 1000L
        private const val REFRESH_FAILURE_BACKOFF_MS: Long = 30 * 1000L

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

        private fun sameCredentials(left: OAuthCredentials?, right: OAuthCredentials?): Boolean {
            if (left == null || right == null) {
                return left == right
            }
            return left.accessToken == right.accessToken &&
                left.refreshToken == right.refreshToken &&
                left.expiresAt == right.expiresAt &&
                left.accountId == right.accountId &&
                left.hd == right.hd
        }

        private fun createHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}
