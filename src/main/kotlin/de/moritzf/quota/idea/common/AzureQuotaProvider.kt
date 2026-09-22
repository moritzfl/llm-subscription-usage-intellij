package de.moritzf.quota.idea.common

import de.moritzf.quota.azure.AzureAccountConfig
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.AzureCliException
import de.moritzf.quota.azure.AzureLiveUsage
import de.moritzf.quota.azure.AzureQuota
import de.moritzf.quota.azure.AzureQuotaClient
import de.moritzf.quota.azure.azureAccountConfig
import de.moritzf.quota.azure.azureQuotaJson
import de.moritzf.quota.idea.settings.ProviderAccount
import de.moritzf.quota.idea.settings.QuotaSettingsState
import java.nio.file.Path

class AzureQuotaProvider(
    override val accountId: String = QuotaProviderType.AZURE.id,
    private val fetchQuota: () -> AzureQuota = {
        val executable = executableForAccount(accountId)
            ?: throw AzureCliException("Azure CLI not found. Install az or set its path in settings.")
        val config = configForAccount(accountId)
        AzureQuotaClient(AzureCli(executable), liveUsage = { AzureLiveUsage.read(accountId) }).fetch(config)
    },
) : CachedQuotaProvider<AzureQuota>() {
    override val type = QuotaProviderType.AZURE
    override val notConfiguredMessage = "Install Azure CLI and run az login, then refresh."
    private val lock = Any()
    private var generation = 0L

    override fun refresh() {
        val started = synchronized(lock) { generation }
        fun update(action: () -> Unit) = synchronized(lock) { if (generation == started) action() }
        try {
            val quota = fetchQuota()
            val raw = azureQuotaJson(quota)
            update { storeQuota(quota.copy(rawJson = raw), raw) }
        } catch (_: InterruptedException) {
            update { clearData("Azure quota refresh cancelled.") }
            Thread.currentThread().interrupt()
        } catch (exception: AzureCliException) {
            update {
                if (exception.transient) storeError(exception.message, transient = true)
                else clearData(exception.message)
            }
        } catch (_: Exception) {
            update { storeError("Could not read Azure usage. Check az login, then refresh.", transient = true) }
        }
    }

    override fun clearData(error: String?) = synchronized(lock) {
        generation++
        super.clearData(error)
    }

    companion object {
        fun executableForAccount(accountId: String): Path? {
            val configured = runCatching {
                QuotaSettingsState.getInstance().account(accountId)?.extra(ProviderAccount.EXTRA_AZURE_EXECUTABLE)
            }.getOrNull()
            return AzureCli.findExecutable(configured)
        }

        internal fun configForAccount(accountId: String): AzureAccountConfig {
            val account = runCatching { QuotaSettingsState.getInstance().account(accountId) }.getOrNull()
            return azureAccountConfig(
                subscriptionId = account?.extra(ProviderAccount.EXTRA_AZURE_SUBSCRIPTION),
                resourceName = account?.extra(ProviderAccount.EXTRA_AZURE_RESOURCE),
                endpoint = account?.extra(ProviderAccount.EXTRA_AZURE_ENDPOINT),
                location = account?.extra(ProviderAccount.EXTRA_AZURE_LOCATION),
                deploymentNames = account?.extra(ProviderAccount.EXTRA_AZURE_DEPLOYMENTS),
            )
        }
    }
}
