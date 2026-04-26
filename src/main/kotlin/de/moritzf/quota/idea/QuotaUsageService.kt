package de.moritzf.quota.idea

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.openai.OpenAiCodexQuotaClient
import de.moritzf.quota.openai.OpenAiCodexQuotaException
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.opencode.OpenCodeQuotaException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Periodically fetches quota data and publishes updates to the IDE message bus.
 */
@Service(Service.Level.APP)
class QuotaUsageService(
    private val quotaFetcher: (String, String?) -> OpenAiCodexQuota = { accessToken, accountId ->
        OpenAiCodexQuotaClient().fetchQuota(accessToken, accountId)
    },
    private val openCodeClient: OpenCodeQuotaClient = OpenCodeQuotaClient(),
    private val accessTokenProvider: () -> String? = { QuotaAuthService.getInstance().getAccessTokenBlocking() },
    private val accountIdProvider: () -> String? = { QuotaAuthService.getInstance().getAccountId() },
    private val openCodeCookieProvider: () -> String? = { OpenCodeSessionCookieStore.getInstance().loadBlocking() },
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    private val updatePublisher: (OpenAiCodexQuota?, String?, OpenCodeQuota?, String?) -> Unit = { quota, error, openCodeQuota, openCodeError ->
        ApplicationManager.getApplication().invokeLater {
            val publisher = ApplicationManager.getApplication().messageBus
                .syncPublisher(QuotaUsageListener.TOPIC)
            publisher.onQuotaUpdated(quota, error)
            publisher.onOpenCodeQuotaUpdated(openCodeQuota, openCodeError)
            ActivityTracker.getInstance().inc()
        }
    },
    scheduleOnInit: Boolean = true,
) : Disposable {
    private val refreshing = AtomicBoolean(false)
    private val lastQuotaRef = AtomicReference<OpenAiCodexQuota?>()
    private val lastErrorRef = AtomicReference<String?>()
    private val lastResponseJsonRef = AtomicReference<String?>()
    private val lastOpenCodeQuotaRef = AtomicReference<OpenCodeQuota?>()
    private val lastOpenCodeErrorRef = AtomicReference<String?>()
    private val lastOpenCodeCookieRef = AtomicReference<String?>()
    private val cachedWorkspaceId = AtomicReference<String?>()
    private val cachedWorkspaceIdTimestamp = AtomicReference(0L)
    private var scheduled: ScheduledFuture<*>? = null

    init {
        hydrateCachedQuotas()
        if (scheduleOnInit) {
            scheduleRefresh()
        }
    }

    fun getLastQuota(): OpenAiCodexQuota? = lastQuotaRef.get()

    fun getLastError(): String? = lastErrorRef.get()

    fun getLastResponseJson(): String? = lastResponseJsonRef.get()

    fun getLastOpenCodeQuota(): OpenCodeQuota? = lastOpenCodeQuotaRef.get()

    fun getLastOpenCodeError(): String? = lastOpenCodeErrorRef.get()

    fun getLastOpenCodeResponseJson(): String? = lastOpenCodeQuotaRef.get()?.rawJson

    internal fun getEffectiveIndicatorData(): QuotaIndicatorData {
        val settings = settingsProvider()
        val source = when (settings?.source() ?: QuotaIndicatorSource.OPEN_AI) {
            QuotaIndicatorSource.LAST_USED -> settings?.lastUsedSource() ?: QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorSource.OPEN_AI
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorSource.OPEN_CODE
        }

        return when (source) {
            QuotaIndicatorSource.OPEN_AI -> QuotaIndicatorData.OpenAi(lastQuotaRef.get(), lastErrorRef.get())
            QuotaIndicatorSource.OPEN_CODE -> QuotaIndicatorData.OpenCode(lastOpenCodeQuotaRef.get(), lastOpenCodeErrorRef.get())
            QuotaIndicatorSource.LAST_USED -> QuotaIndicatorData.OpenAi(lastQuotaRef.get(), lastErrorRef.get())
        }
    }

    fun refreshNowAsync() {
        AppExecutorUtil.getAppExecutorService().execute(::refreshNow)
    }

    fun refreshNowBlocking() {
        refreshNow()
    }

    fun clearUsageData(error: String? = null) {
        clearCodexUsageData(error)
        clearOpenCodeUsageData()
    }

    fun clearCodexUsageData(error: String? = null) {
        settingsProvider()?.cachedOpenAiQuotaJson = null
        publishCodexUpdate(null, error, rawResponseJson = null, clearRawResponseJson = true)
    }

    fun clearOpenCodeUsageData(error: String? = "No session cookie configured") {
        resetOpenCodeCaches()
        lastOpenCodeCookieRef.set(null)
        settingsProvider()?.cachedOpenCodeQuotaJson = null
        publishOpenCodeUpdate(null, error)
    }

    fun resetOpenCodeWorkspaceCache() {
        cachedWorkspaceId.set(null)
        cachedWorkspaceIdTimestamp.set(0)
        OpenCodeQuotaClient.clearCachedFunctionId()
    }

    private fun scheduleRefresh() {
        val minutes = maxOf(1, settingsProvider()?.refreshMinutes ?: 5)
        scheduled = scheduler.scheduleWithFixedDelay(::refreshNow, 0, minutes.toLong(), TimeUnit.MINUTES)
    }

    private fun hydrateCachedQuotas() {
        val settings = settingsProvider() ?: return
        val cachedOpenAiQuota = QuotaSnapshotCache.decodeOpenAiQuota(settings.cachedOpenAiQuotaJson)
        val cachedOpenCodeQuota = QuotaSnapshotCache.decodeOpenCodeQuota(settings.cachedOpenCodeQuotaJson)
        lastQuotaRef.set(cachedOpenAiQuota)
        lastResponseJsonRef.set(cachedOpenAiQuota?.rawJson)
        lastOpenCodeQuotaRef.set(cachedOpenCodeQuota)
    }

    private fun refreshNow() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }

        try {
            // Fetch OpenAI Codex quota
            val accessToken = accessTokenProvider()
            if (accessToken.isNullOrBlank()) {
                publishCodexUpdate(null, "Not logged in")
            } else {
                try {
                    val quota = quotaFetcher(accessToken, accountIdProvider())
                    publishCodexUpdate(quota, null, quota.rawJson)
                } catch (exception: OpenAiCodexQuotaException) {
                    publishCodexUpdate(null, "Request failed (${exception.statusCode})", exception.rawBody)
                } catch (exception: Exception) {
                    publishCodexUpdate(null, exception.message ?: "Request failed")
                }
            }

            // Fetch OpenCode Go quota
            val openCodeCookie = openCodeCookieProvider()
            if (openCodeCookie.isNullOrBlank()) {
                clearOpenCodeUsageData()
            } else {
                refreshOpenCodeQuota(openCodeCookie)
            }
        } finally {
            refreshing.set(false)
        }
    }

    private fun refreshOpenCodeQuota(sessionCookie: String) {
        resetOpenCodeCachesIfCookieChanged(sessionCookie)

        try {
            publishOpenCodeUpdate(fetchOpenCodeQuota(sessionCookie), null)
            return
        } catch (exception: OpenCodeQuotaException) {
            if (shouldRetryOpenCode(exception)) {
                resetOpenCodeCaches()
                try {
                    publishOpenCodeUpdate(fetchOpenCodeQuota(sessionCookie), null)
                    return
                } catch (retryException: OpenCodeQuotaException) {
                    publishOpenCodeUpdate(null, retryException.message ?: "Request failed (${retryException.statusCode})")
                    return
                } catch (retryException: Exception) {
                    publishOpenCodeUpdate(null, retryException.message ?: "Request failed")
                    return
                }
            }
            publishOpenCodeUpdate(null, exception.message ?: "Request failed (${exception.statusCode})")
        } catch (exception: Exception) {
            publishOpenCodeUpdate(null, exception.message ?: "Request failed")
        }
    }

    private fun fetchOpenCodeQuota(sessionCookie: String): OpenCodeQuota {
        val workspaceId = resolveWorkspaceId(sessionCookie)
        return openCodeClient.fetchQuota(sessionCookie, workspaceId)
    }

    private fun resolveWorkspaceId(sessionCookie: String): String {
        val cached = cachedWorkspaceId.get()
        val timestamp = cachedWorkspaceIdTimestamp.get()
        if (cached != null && System.currentTimeMillis() - timestamp < WORKSPACE_CACHE_TTL_MS) {
            return cached
        }

        val settings = settingsProvider()
        val storedWorkspaceId = settings?.openCodeWorkspaceId
        if (!storedWorkspaceId.isNullOrBlank()) {
            cachedWorkspaceId.set(storedWorkspaceId)
            cachedWorkspaceIdTimestamp.set(System.currentTimeMillis())
            return storedWorkspaceId
        }

        val workspaceId = openCodeClient.discoverWorkspaceId(sessionCookie)
        cachedWorkspaceId.set(workspaceId)
        cachedWorkspaceIdTimestamp.set(System.currentTimeMillis())
        return workspaceId
    }

    private fun resetOpenCodeCachesIfCookieChanged(sessionCookie: String) {
        val previousCookie = lastOpenCodeCookieRef.getAndSet(sessionCookie)
        if (previousCookie != null && previousCookie != sessionCookie) {
            resetOpenCodeCaches()
        }
    }

    private fun resetOpenCodeCaches() {
        cachedWorkspaceId.set(null)
        cachedWorkspaceIdTimestamp.set(0)
        OpenCodeQuotaClient.clearCachedFunctionId()
    }

    private fun shouldRetryOpenCode(exception: OpenCodeQuotaException): Boolean {
        return exception.statusCode == 0 ||
            exception.statusCode == 401 ||
            exception.statusCode == 403 ||
            exception.message?.contains("Could not parse OpenCode quota response") == true
    }

    private fun publishCodexUpdate(
        quota: OpenAiCodexQuota?,
        error: String?,
        rawResponseJson: String? = null,
        clearRawResponseJson: Boolean = false,
    ) {
        if (clearRawResponseJson) {
            lastResponseJsonRef.set(rawResponseJson)
        } else if (rawResponseJson != null) {
            lastResponseJsonRef.set(rawResponseJson)
        }
        lastQuotaRef.set(quota)
        lastErrorRef.set(error)
        val settings = settingsProvider()
        if (quota != null) {
            QuotaSnapshotCache.encodeOpenAiQuota(quota)?.let { settings?.cachedOpenAiQuotaJson = it }
            settings?.updateTimestamp("openai")
        }
        updatePublisher(quota, error, lastOpenCodeQuotaRef.get(), lastOpenCodeErrorRef.get())
    }

    private fun publishOpenCodeUpdate(
        openCodeQuota: OpenCodeQuota?,
        openCodeError: String?,
    ) {
        lastOpenCodeQuotaRef.set(openCodeQuota)
        lastOpenCodeErrorRef.set(openCodeError)
        val settings = settingsProvider()
        if (openCodeQuota != null) {
            QuotaSnapshotCache.encodeOpenCodeQuota(openCodeQuota)?.let { settings?.cachedOpenCodeQuotaJson = it }
            settings?.updateTimestamp("opencode")
        }
        updatePublisher(lastQuotaRef.get(), lastErrorRef.get(), openCodeQuota, openCodeError)
    }

    override fun dispose() {
        scheduled?.cancel(true)
        scheduled = null
    }

    companion object {
        private const val WORKSPACE_CACHE_TTL_MS = 30 * 60 * 1000L

        @JvmStatic
        fun getInstance(): QuotaUsageService {
            return ApplicationManager.getApplication().getService(QuotaUsageService::class.java)
        }
    }
}
