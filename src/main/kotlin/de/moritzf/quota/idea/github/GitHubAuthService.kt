package de.moritzf.quota.idea.github

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.github.GitHubDeviceTokenPollResult
import de.moritzf.quota.github.GitHubOAuthClient
import de.moritzf.quota.idea.auth.AuthService
import de.moritzf.quota.idea.auth.LoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub device-flow login.
 */
@Service(Service.Level.APP)
class GitHubAuthService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val oauthClient: GitHubOAuthClient = GitHubOAuthClient(),
    private val credentialsStore: GitHubCredentialsStore = GitHubCredentialsStore.getInstance(),
    private val browserOpener: (String) -> Unit = BrowserUtil::browse,
) : Disposable, AuthService {
    private val loginInProgress = AtomicBoolean(false)

    override fun startLoginFlow(callback: (LoginResult) -> Unit, onVerificationUrl: ((String, String) -> Unit)?) {
        if (!loginInProgress.compareAndSet(false, true)) {
            callback(LoginResult.error("Login already in progress"))
            return
        }
        scope.launch {
            val result = try {
                runLoginFlow(onVerificationUrl)
            } catch (exception: Exception) {
                LOG.warn("GitHub login failed", exception)
                LoginResult.error(exception.message ?: "Login failed")
            } finally {
                loginInProgress.set(false)
            }
            callback(result)
        }
    }

    override fun isLoginInProgress(): Boolean = loginInProgress.get()

    override fun isLoggedIn(): Boolean = credentialsStore.load()?.isUsable() == true

    override fun abortLogin(reason: String?): Boolean {
        val aborted = loginInProgress.getAndSet(false)
        if (aborted) {
            LOG.info(reason ?: "GitHub login canceled")
        }
        return aborted
    }

    override fun clearCredentials() {
        abortLogin("GitHub logged out")
        credentialsStore.clear()
    }

    private fun runLoginFlow(onVerificationUrl: ((String, String) -> Unit)?): LoginResult {
        val authorization = oauthClient.requestDeviceAuthorization()
        if (authorization.deviceCode.isBlank() || authorization.userCode.isBlank()) {
            return LoginResult.error("GitHub did not return a usable device code")
        }
        onVerificationUrl?.invoke(authorization.verificationUri, authorization.userCode)
        browserOpener(authorization.verificationUri)

        var intervalSeconds = authorization.intervalSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        val expiresAfterMs = authorization.expiresInSeconds.coerceAtLeast(1) * 1000L
        while (loginInProgress.get() && System.currentTimeMillis() - startedAt < expiresAfterMs) {
            Thread.sleep(intervalSeconds * 1000L)
            if (!loginInProgress.get()) break
            when (val result = oauthClient.pollDeviceToken(authorization.deviceCode)) {
                is GitHubDeviceTokenPollResult.Authorized -> {
                    credentialsStore.save(result.credentials)
                    return LoginResult.success()
                }
                is GitHubDeviceTokenPollResult.Pending -> {
                    intervalSeconds = when {
                        result.nextIntervalSeconds > 0 -> result.nextIntervalSeconds
                        result.slowDown -> intervalSeconds + 5
                        else -> intervalSeconds
                    }
                }
            }
        }
        return if (loginInProgress.get()) LoginResult.error("Authentication timed out") else LoginResult.error("Login canceled")
    }

    override fun dispose() {
        scope.cancel()
    }


    companion object {
        private val LOG = Logger.getInstance(GitHubAuthService::class.java)

        @JvmStatic
        fun getInstance(): GitHubAuthService = ApplicationManager.getApplication().getService(GitHubAuthService::class.java)
    }
}
