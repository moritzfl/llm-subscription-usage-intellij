package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.auth.OAuthConnectionState
import de.moritzf.quota.shared.ProviderQuota
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared in-memory state and cache persistence for quota providers.
 */
abstract class CachedQuotaProvider<Q : ProviderQuota> : QuotaProvider {
    protected val lastQuotaRef = AtomicReference<Q?>()
    protected val lastErrorRef = AtomicReference<String?>()
    protected val lastRawJsonRef = AtomicReference<String?>()
    private val lastErrorTransientRef = AtomicBoolean(false)

    override fun getLastQuota(): Q? = lastQuotaRef.get()

    override fun getLastError(): String? = lastErrorRef.get()

    override fun isLastErrorTransient(): Boolean = lastErrorTransientRef.get()

    override fun getLastRawJson(): String? {
        lastRawJsonRef.get()?.let { return it }
        val quota = lastQuotaRef.get() ?: return null
        return QuotaSnapshotCache.encodePlain(type, quota)
    }

    override fun currentUsageFraction(): Double? = lastQuotaRef.get()?.usageFraction()

    override fun cachedUsageFraction(settings: QuotaSettingsState): Double? = decodeCached(settings)?.usageFraction()

    override fun currentActivityWindows(): Map<String, Double> =
        lastQuotaRef.get()?.activityWindows().orEmpty()

    override fun cachedActivityWindows(settings: QuotaSettingsState): Map<String, Double> =
        decodeCached(settings)?.activityWindows().orEmpty()

    override fun hydrateFromCache(settings: QuotaSettingsState) {
        val cached = decodeCached(settings)
        lastQuotaRef.set(cached)
        lastRawJsonRef.set(cached?.rawJson)
    }

    override fun persistToCache(settings: QuotaSettingsState) {
        val quota = lastQuotaRef.get() ?: return
        QuotaSnapshotCache.encode(type, quota)?.let { settings.setCachedQuotaJson(accountId, it) }
        settings.updateTimestamp(accountId)
    }

    override fun clearData(error: String?) {
        lastQuotaRef.set(null)
        lastErrorRef.set(error)
        lastErrorTransientRef.set(false)
        lastRawJsonRef.set(null)
    }

    protected fun storeQuota(quota: Q, rawJson: String?) {
        lastQuotaRef.set(quota)
        lastErrorRef.set(null)
        lastErrorTransientRef.set(false)
        lastRawJsonRef.set(rawJson)
    }

    /**
     * Handles a refresh that produced no usable access token. A stored login whose token could not
     * be refreshed right now is a transient failure, not a logout, so the login and the last
     * reading are kept instead of reporting the provider as not configured.
     */
    protected fun storeMissingAccessToken(connectionState: OAuthConnectionState, refreshFailedMessage: String) {
        when (connectionState) {
            OAuthConnectionState.LOGGED_OUT -> clearData(notConfiguredMessage)
            OAuthConnectionState.RECONNECT_REQUIRED -> storeError(
                "${type.displayName} login needs to be renewed. Log in again from settings.",
            )
            else -> storeError(refreshFailedMessage, transient = true)
        }
    }

    /**
     * Records a failed fetch and whether it was temporary (offline, timeout, rate limit, server
     * error). The error is always kept for the settings page; [isLastErrorTransient] lets the
     * status bar and popup keep showing the last reading instead of replacing it with a message
     * that resolves itself.
     */
    protected fun storeFetchFailure(statusCode: Int, error: String?, rawJson: String? = null) {
        storeError(error, rawJson, transient = isTransientFetchFailure(statusCode))
    }

    protected fun storeError(error: String?, rawJson: String? = null, transient: Boolean = false) {
        // Keep the last good quota so transient network blips do not blank the UI/MCP.
        // clearData() is the only path that drops success state (logout / not configured).
        lastErrorRef.set(error)
        lastErrorTransientRef.set(transient && error != null)
        if (rawJson != null) {
            lastRawJsonRef.set(rawJson)
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected fun decodeCached(settings: QuotaSettingsState): Q? =
        QuotaSnapshotCache.decode(type, settings.cachedQuotaJson(accountId)) as? Q
}
