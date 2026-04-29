package de.moritzf.quota.idea.kimi

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.kimi.KimiDeviceTokenPollResult
import de.moritzf.quota.kimi.KimiOAuthClient
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
) : Disposable {
    private val loginInProgress = AtomicBoolean(false)

    fun startLoginFlow(callback: (KimiLoginResult) -> Unit, onVerificationUrl: ((String, String) -> Unit)? = null) {
        if (!loginInProgress.compareAndSet(false, true)) {
            callback(KimiLoginResult.error("Login already in progress"))
            return
        }
        scope.launch {
            val result = try {
                runLoginFlow(onVerificationUrl)
            } catch (exception: Exception) {
                LOG.warn("Kimi login failed", exception)
                KimiLoginResult.error(exception.message ?: "Login failed")
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
            LOG.info(reason ?: "Kimi login canceled")
        }
        return aborted
    }

    fun clearCredentials() {
        abortLogin("Kimi logged out")
        credentialsStore.clear()
    }

    private fun runLoginFlow(onVerificationUrl: ((String, String) -> Unit)?): KimiLoginResult {
        val authorization = oauthClient.requestDeviceAuthorization()
        if (authorization.deviceCode.isBlank() || authorization.verificationUriComplete.isBlank()) {
            return KimiLoginResult.error("Kimi did not return a usable verification URL")
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
                    return KimiLoginResult.success()
                }
                is KimiDeviceTokenPollResult.Pending -> {
                    if (result.nextIntervalSeconds > 0) {
                        intervalSeconds = result.nextIntervalSeconds
                    }
                }
            }
            Thread.sleep(intervalSeconds * 1000L)
        }
        return if (loginInProgress.get()) KimiLoginResult.error("Authentication timed out") else KimiLoginResult.error("Login canceled")
    }

    override fun dispose() {
        scope.cancel()
    }

    class KimiLoginResult private constructor(val success: Boolean, val message: String?) {
        companion object {
            fun success(): KimiLoginResult = KimiLoginResult(true, null)
            fun error(message: String): KimiLoginResult = KimiLoginResult(false, message)
        }
    }

    companion object {
        private val LOG = Logger.getInstance(KimiAuthService::class.java)

        @JvmStatic
        fun getInstance(): KimiAuthService = ApplicationManager.getApplication().getService(KimiAuthService::class.java)
    }
}
