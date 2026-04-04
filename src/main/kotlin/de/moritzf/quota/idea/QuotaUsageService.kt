package de.moritzf.quota.idea

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenAiCodexQuotaClient
import de.moritzf.quota.OpenAiCodexQuotaException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Periodically fetches quota data and publishes updates to the IDE message bus.
 */
@Service(Service.Level.APP)
class QuotaUsageService : Disposable {
    private val client = OpenAiCodexQuotaClient()
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService()
    private val refreshing = AtomicBoolean(false)
    private val lastQuotaRef = AtomicReference<OpenAiCodexQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastResponseJsonRef = AtomicReference<String?>()
    private var scheduled: ScheduledFuture<*>? = null

    init {
        scheduleRefresh()
    }

    fun getLastQuota(): OpenAiCodexQuota? = lastQuotaRef.get()

    fun getLastError(): String? = lastErrorRef.get()

    fun getLastResponseJson(): String? = lastResponseJsonRef.get()

    fun refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshNow)
    }

    fun refreshNowBlocking() {
        refreshNow()
    }

    private fun scheduleRefresh() {
        val minutes = maxOf(1, QuotaSettingsState.getInstance().refreshMinutes)
        scheduled = scheduler.scheduleWithFixedDelay(::refreshNow, 0, minutes.toLong(), TimeUnit.MINUTES)
    }

    private fun refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }

        try {
            val authService = QuotaAuthService.getInstance()
            val accessToken = authService.getAccessTokenBlocking()
            if (accessToken.isNullOrBlank()) {
                publishUpdate(null, "Not logged in")
                return
            }

            val quota = client.fetchQuota(accessToken, authService.getAccountId())
            publishUpdate(quota, null)
        } catch (exception: OpenAiCodexQuotaException) {
            publishUpdate(null, "Request failed (${exception.statusCode})")
        } catch (exception: Exception) {
            publishUpdate(null, exception.message ?: "Request failed")
        } finally {
            refreshing.set(false)
        }
    }

    private fun publishUpdate(quota: OpenAiCodexQuota?, error: String?) {
        if (quota?.rawJson != null) {
            lastResponseJsonRef.set(quota.rawJson)
        }
        lastQuotaRef.set(quota)
        lastErrorRef.set(error)
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
                .onQuotaUpdated(quota, error)
            ActivityTracker.getInstance().inc()
        }
    }

    override fun dispose() {
        scheduled?.cancel(true)
        scheduled = null
    }

    companion object {
        @JvmStatic
        fun getInstance(): QuotaUsageService {
            return ApplicationManager.getApplication().getService(QuotaUsageService::class.java)
        }
    }
}
