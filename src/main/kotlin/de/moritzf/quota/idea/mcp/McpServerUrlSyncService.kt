package de.moritzf.quota.idea.mcp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.idea.settings.QuotaSettingsListener
import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.APP)
class McpServerUrlSyncService(
    private val settingsProvider: () -> QuotaSettingsState? = {
        runCatching { QuotaSettingsState.getInstance() }.getOrNull()
    },
    private val endpointProvider: () -> McpServerEndpoints? = McpServerUrlResolver::currentEndpoints,
    private val updater: McpJsonTargetUpdater = McpJsonTargetUpdater(),
    private val scheduler: ScheduledExecutorService = AppExecutorUtil.getAppScheduledExecutorService(),
    subscribeToSettings: Boolean = true,
) : Disposable {
    private val logger = Logger.getInstance(McpServerUrlSyncService::class.java)
    private val syncing = AtomicBoolean(false)
    private var scheduled: ScheduledFuture<*>? = null

    init {
        if (subscribeToSettings) {
            ApplicationManager.getApplication().messageBus.connect(this)
                .subscribe(QuotaSettingsListener.TOPIC, QuotaSettingsListener { reloadFromSettings() })
            reloadFromSettings()
        }
    }

    fun reloadFromSettings() {
        val settings = settingsProvider()
        if (settings?.syncIntellijMcpServerUrl == true && settings.mcpServerSyncTargets.any { it.isConfigured() }) {
            startSyncing()
        } else {
            stopSyncing()
        }
    }

    fun syncNowAsync() {
        scheduler.execute(::syncSafely)
    }

    private fun startSyncing() {
        val current = scheduled
        if (current != null && !current.isCancelled) {
            return
        }
        scheduled = scheduler.scheduleWithFixedDelay(::syncSafely, 0, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopSyncing() {
        scheduled?.cancel(false)
        scheduled = null
    }

    private fun syncSafely() {
        if (!syncing.compareAndSet(false, true)) {
            return
        }

        try {
            syncTargets()
        } catch (error: Throwable) {
            logger.warn("Failed to sync IntelliJ MCP server URL", error)
        } finally {
            syncing.set(false)
        }
    }

    private fun syncTargets() {
        val settings = settingsProvider() ?: return
        if (!settings.syncIntellijMcpServerUrl) {
            return
        }
        val endpoints = endpointProvider() ?: return
        val targets = settings.mcpServerSyncTargets
            .map { it.normalized() }
            .filter { it.isConfigured() }

        targets.forEach { target ->
            val url = target.transport().urlFor(endpoints)
            runCatching {
                updater.updateFile(target.jsonFilePath, target.jsonPropertyPath, url)
            }.onFailure { error ->
                logger.warn("Failed to sync IntelliJ MCP server URL to ${target.jsonFilePath}", error)
            }
        }
    }

    override fun dispose() {
        stopSyncing()
    }

    companion object {
        private const val SYNC_INTERVAL_SECONDS = 5L

        @JvmStatic
        fun getInstance(): McpServerUrlSyncService {
            return ApplicationManager.getApplication().getService(McpServerUrlSyncService::class.java)
        }
    }
}
