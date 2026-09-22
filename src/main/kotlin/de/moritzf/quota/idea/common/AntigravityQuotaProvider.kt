package de.moritzf.quota.idea.common

import de.moritzf.quota.antigravity.AntigravityQuota
import de.moritzf.quota.antigravity.AntigravityQuotaClient
import de.moritzf.quota.antigravity.AntigravityQuotaException
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.nio.file.Path

class AntigravityQuotaProvider(
    override val accountId: String = QuotaProviderType.ANTIGRAVITY.id,
    private val fetchQuota: () -> AntigravityQuota = {
        AntigravityQuotaClient(executableProvider = { executableForAccount(accountId) }).fetchQuota()
    },
) : CachedQuotaProvider<AntigravityQuota>() {
    override val type = QuotaProviderType.ANTIGRAVITY
    override val notConfiguredMessage = "Run agy to sign in, then refresh Antigravity usage."
    private val lock = Any()
    private var generation = 0L

    override fun refresh() {
        val started = synchronized(lock) { generation }
        fun update(action: () -> Unit) = synchronized(lock) { if (generation == started) action() }
        try {
            val quota = fetchQuota()
            update { storeQuota(quota, quota.rawJson) }
        } catch (_: InterruptedException) {
            update { clearData("AGY quota refresh cancelled.") }
            Thread.currentThread().interrupt()
        } catch (exception: AntigravityQuotaException) {
            update { clearData(exception.message) }
        } catch (_: Exception) {
            update { clearData("Could not read AGY usage. Check Antigravity CLI, then refresh.") }
        }
    }

    override fun clearData(error: String?) = synchronized(lock) {
        generation++
        super.clearData(error)
    }

    // The report has no account identity. Never restore a previous CLI login's quota after restart.
    override fun hydrateFromCache(settings: QuotaSettingsState) { settings.dropAccountData(accountId) }
    override fun persistToCache(settings: QuotaSettingsState) { settings.updateTimestamp(accountId) }
    override fun cachedUsageFraction(settings: QuotaSettingsState): Double? = null
    override fun cachedActivityWindows(settings: QuotaSettingsState): Map<String, Double> = emptyMap()

    companion object {
        fun executableForAccount(accountId: String): Path? {
            val configured = runCatching {
                QuotaSettingsState.getInstance().account(accountId)?.extra(ProviderAccount.EXTRA_AGY_EXECUTABLE)
            }.getOrNull()
            return AntigravityQuotaClient.findExecutable(configured)
        }
    }
}
