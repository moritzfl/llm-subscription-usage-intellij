package de.moritzf.quota.idea.github

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.github.GitHubDeviceTokenPollResult
import de.moritzf.quota.github.GitHubOAuthClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GitHub device-flow login. Unlike browser-redirect OAuth, the user must enter
 * the user code at the verification URL, so [startLoginFlow] reports the code via
 * [onVerificationUrl] before the polling loop begins.
 */
@Service(Service.Level.APP)
class GitHubAuthService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val oauthClient: GitHubOAuthClient = GitHubOAuthClient(),
    private val credentialsStore: GitHubCredentialsStore = GitHubCredentialsStore.getInstance(),
    private val browserOpener: (String) -> Unit = BrowserUtil::browse,
) : Disposable {
    private val loginInProgress = AtomicBoolean(false)

    fun startLoginFlow(callback: (GitHubLoginResult) -> Unit, onVerificationUrl: ((String, String) -> Unit)? = null) {
        if (!loginInProgress.compareAndSet(false, true)) {
            callback(GitHubLoginResult.error("Login already in progress"))
            return
        }
        scope.launch {
            val result = try {
                runLoginFlow(onVerificationUrl)
            } catch (exception: Exception) {
                LOG.warn("GitHub login failed", exception)
                GitHubLoginResult.error(exception.message ?: "Login failed")
            } finally {
                loginInProgress.set(false)
            }
            callback(result)
        }
    }

    fun isLoginInProgress(): Boolean = loginInProgress.get()

    fun isLoggedIn(): Boolean = credentialsStore.load()?.isUsable() == true

    fun abortLogin(reason: String? = null): Boolean {
        val aborted = loginInProgress.getAndSet(false)
        if (aborted) {
            LOG.info(reason ?: "GitHub login canceled")
        }
        return aborted
    }

    fun clearCredentials() {
        abortLogin("GitHub logged out")
        credentialsStore.clear()
    }

    private fun runLoginFlow(onVerificationUrl: ((String, String) -> Unit)?): GitHubLoginResult {
        val authorization = oauthClient.requestDeviceAuthorization()
        if (authorization.deviceCode.isBlank() || authorization.userCode.isBlank()) {
            return GitHubLoginResult.error("GitHub did not return a usable device code")
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
                    return GitHubLoginResult.success()
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
        return if (loginInProgress.get()) GitHubLoginResult.error("Authentication timed out") else GitHubLoginResult.error("Login canceled")
    }

    override fun dispose() {
        scope.cancel()
    }

    class GitHubLoginResult private constructor(val success: Boolean, val message: String?) {
        companion object {
            fun success(): GitHubLoginResult = GitHubLoginResult(true, null)
            fun error(message: String): GitHubLoginResult = GitHubLoginResult(false, message)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(GitHubAuthService::class.java)

        @JvmStatic
        fun getInstance(): GitHubAuthService = ApplicationManager.getApplication().getService(GitHubAuthService::class.java)
    }
}
