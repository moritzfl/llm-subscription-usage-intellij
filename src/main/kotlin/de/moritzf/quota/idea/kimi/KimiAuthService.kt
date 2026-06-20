package de.moritzf.quota.idea.kimi

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.kimi.KimiDeviceTokenPollResult
import de.moritzf.quota.kimi.KimiOAuthClient
import de.moritzf.quota.idea.auth.AuthService
import de.moritzf.quota.idea.auth.LoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class KimiAuthService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val oauthClient: KimiOAuthClient = KimiOAuthClient(),
    private val credentialsStore: KimiCredentialsStore = KimiCredentialsStore.getInstance(),
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
                LOG.warn("Kimi login failed", exception)
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
            LOG.info(reason ?: "Kimi login canceled")
        }
        return aborted
    }

    override fun clearCredentials() {
        abortLogin("Kimi logged out")
        credentialsStore.clear()
    }

    private fun runLoginFlow(onVerificationUrl: ((String, String) -> Unit)?): LoginResult {
        val authorization = oauthClient.requestDeviceAuthorization()
        if (authorization.deviceCode.isBlank() || authorization.verificationUriComplete.isBlank()) {
            return LoginResult.error("Kimi did not return a usable verification URL")
        }
        onVerificationUrl?.invoke(authorization.verificationUriComplete, authorization.userCode)
        browserOpener(authorization.verificationUriComplete)

        var intervalSeconds = authorization.intervalSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        val expiresAfterMs = authorization.expiresInSeconds.coerceAtLeast(1) * 1000L
        while (loginInProgress.get() && System.currentTimeMillis() - startedAt < expiresAfterMs) {
            when (val result = oauthClient.pollDeviceToken(authorization.deviceCode)) {
                is KimiDeviceTokenPollResult.Authorized -> {
                    credentialsStore.save(result.credentials)
                    return LoginResult.success()
                }
                is KimiDeviceTokenPollResult.Pending -> {
                    if (result.nextIntervalSeconds > 0) {
                        intervalSeconds = result.nextIntervalSeconds
                    }
                }
            }
            Thread.sleep(intervalSeconds * 1000L)
        }
        return if (loginInProgress.get()) LoginResult.error("Authentication timed out") else LoginResult.error("Login canceled")
    }

    override fun dispose() {
        scope.cancel()
    }


    companion object {
        private val LOG = Logger.getInstance(KimiAuthService::class.java)

        @JvmStatic
        fun getInstance(): KimiAuthService = ApplicationManager.getApplication().getService(KimiAuthService::class.java)
    }
}
