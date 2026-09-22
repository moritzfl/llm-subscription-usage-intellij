package de.moritzf.quota.idea.opencode

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import de.moritzf.quota.idea.auth.LoginResult
import de.moritzf.quota.idea.auth.OAuthCredentialStore
import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.idea.auth.OAuthCredentialsStore
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.opencode.OpenCodeDeviceTokenResult
import de.moritzf.quota.opencode.OpenCodeOAuthClient
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import de.moritzf.quota.opencode.OpenCodeWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Owns only this plugin's Console credentials, independently for each provider account. */
@Service(Service.Level.APP)
class OpenCodeAuthService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val oauthClient: OpenCodeOAuthClient = OpenCodeOAuthClient(),
    private val credentialStoreFactory: (String) -> OAuthCredentialStore = {
        OAuthCredentialsStore.forAccount(it, QuotaProviderType.OPEN_CODE)
    },
    private val browserOpener: (String) -> Unit = BrowserUtil::browse,
) : Disposable {
    private class State(val store: OAuthCredentialStore) {
        val lock = Any()
        val refreshLock = Any()
        @Volatile var loaded = false
        @Volatile var loadError: Exception? = null
        @Volatile var credentials: OAuthCredentials? = null
        @Volatile var loginInProgress = false
        var generation = 0L
        var loginJob: Job? = null
    }

    private val states = ConcurrentHashMap<String, State>()
    private fun state(accountId: String) = states.computeIfAbsent(accountId) { State(credentialStoreFactory(it)) }

    fun load(accountId: String, onLoaded: (() -> Unit)? = null): OAuthCredentials? {
        val state = state(accountId)
        if (state.loaded) return state.credentials
        scope.launch {
            try {
                loadBlocking(accountId)
            } catch (_: Exception) {
                // Report storage failures in settings; never let a background load escape the scope.
            } finally {
                onLoaded?.invoke()
            }
        }
        return null
    }

    fun isLoaded(accountId: String): Boolean = state(accountId).loaded
    fun loadError(accountId: String): String? = state(accountId).loadError?.message
    fun isLoginInProgress(accountId: String): Boolean = state(accountId).loginInProgress

    fun loadBlocking(accountId: String): OAuthCredentials? {
        val state = state(accountId)
        return synchronized(state.lock) {
            if (!state.loaded) {
                try {
                    state.credentials = state.store.load()
                } catch (exception: Exception) {
                    state.loadError = exception
                }
                state.loaded = true
            }
            state.loadError?.let { throw it }
            state.credentials
        }
    }

    /** Single-flight refresh, including callers retrying the same rejected access token. */
    fun credentials(accountId: String, rejectedAccessToken: String? = null): OAuthCredentials? {
        val state = state(accountId)
        synchronized(state.refreshLock) {
            val existing = loadBlocking(accountId) ?: return null
            if (existing.accessToken.isNullOrBlank()) return null
            if (existing.expiresAt > System.currentTimeMillis() + 60_000 && existing.accessToken != rejectedAccessToken) return existing
            val refreshed = oauthClient.refreshCredentials(existing)
            synchronized(state.lock) {
                if (state.credentials !== existing) return state.credentials
                // Save rotated refresh tokens before making any organization or quota requests.
                state.store.save(refreshed)
                state.credentials = refreshed
                return refreshed
            }
        }
    }

    fun workspaces(accountId: String, client: OpenCodeQuotaClient = OpenCodeQuotaClient()): List<OpenCodeWorkspace> {
        var credentials = credentials(accountId) ?: error("Not signed in to OpenCode")
        val organizations = try {
            client.fetchWorkspaces(checkNotNull(credentials.accessToken))
        } catch (exception: OpenCodeQuotaException) {
            if (exception.statusCode != 401) throw exception
            credentials = credentials(accountId, credentials.accessToken) ?: error("Not signed in to OpenCode")
            client.fetchWorkspaces(checkNotNull(credentials.accessToken))
        }
        // New device grants are scoped in the browser. Never offer an organization outside that grant.
        val orgId = credentials.accountId
        return if (orgId == null) organizations.sortedWith(compareBy({ it.name }, { it.id }))
        else listOf(organizations.find { it.id == orgId } ?: OpenCodeWorkspace(orgId))
    }

    fun startLoginFlow(
        accountId: String,
        callback: (LoginResult) -> Unit,
        onVerificationUrl: (String, String) -> Unit,
    ) {
        val state = state(accountId)
        synchronized(state.lock) {
            if (state.loginInProgress) {
                callback(LoginResult.error("Login already in progress"))
                return
            }
            val generation = ++state.generation
            state.loginInProgress = true
            state.loginJob = scope.launch {
                val result = try {
                    val authorization = oauthClient.requestDeviceAuthorization()
                    val url = oauthClient.verificationUrl(authorization)
                    synchronized(state.lock) {
                        if (state.generation != generation) return@launch
                        onVerificationUrl(url, authorization.userCode)
                        browserOpener(url)
                    }
                    var interval = authorization.intervalSeconds.coerceAtLeast(1) * 1000
                    val started = System.nanoTime()
                    val expiresAfter = authorization.expiresInSeconds * 1_000_000_000
                    var authorized = false
                    while (System.nanoTime() - started < expiresAfter) {
                        delay(interval)
                        if (System.nanoTime() - started >= expiresAfter) break
                        when (val polled = oauthClient.pollDeviceToken(authorization.deviceCode)) {
                            is OpenCodeDeviceTokenResult.Authorized -> {
                                synchronized(state.lock) {
                                    if (state.generation != generation) return@launch
                                    state.store.save(polled.credentials)
                                    state.credentials = polled.credentials
                                    state.loadError = null
                                    state.loaded = true
                                }
                                authorized = true
                                break
                            }
                            OpenCodeDeviceTokenResult.Pending -> Unit
                            OpenCodeDeviceTokenResult.SlowDown -> interval += 5_000
                        }
                    }
                    if (authorized) LoginResult.success() else LoginResult.error("OpenCode login timed out. Try again.")
                } catch (_: CancellationException) {
                    return@launch
                } catch (exception: Exception) {
                    LoginResult.error(exception.message ?: "OpenCode login failed")
                }
                synchronized(state.lock) {
                    if (state.generation == generation) {
                        state.loginInProgress = false
                        state.loginJob = null
                        callback(result)
                    }
                }
            }
        }
    }

    fun abortLogin(accountId: String) {
        val state = state(accountId)
        synchronized(state.lock) {
            state.generation++
            state.loginInProgress = false
            state.loginJob?.cancel()
            state.loginJob = null
        }
    }

    fun clearCredentials(accountId: String) {
        val state = state(accountId)
        synchronized(state.lock) {
            abortLogin(accountId)
            state.store.clear()
            state.credentials = null
            state.loadError = null
            state.loaded = true
        }
    }

    override fun dispose() {
        states.keys.forEach(::abortLogin)
        scope.cancel()
    }

    companion object {
        fun getInstance(): OpenCodeAuthService = ApplicationManager.getApplication().getService(OpenCodeAuthService::class.java)
    }
}
