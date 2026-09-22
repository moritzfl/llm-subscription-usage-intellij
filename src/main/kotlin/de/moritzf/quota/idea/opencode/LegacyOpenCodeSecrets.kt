package de.moritzf.quota.idea.opencode

import com.intellij.credentialStore.CredentialAttributes
import de.moritzf.quota.idea.auth.PasswordSafeSecrets
import de.moritzf.quota.idea.common.QuotaProviderType

/** Removal only: old plugin-owned cookies and API keys are no longer used for authentication. */
internal object LegacyOpenCodeSecrets {
    fun clear(accountId: String) {
        val default = accountId == QuotaProviderType.OPEN_CODE.id
        for ((serviceName, userName) in listOf("OpenCode Session Cookie" to "opencode-session", "OpenCode API Key" to "opencode-api-key")) {
            val service = if (default) serviceName else "$serviceName ($accountId)"
            val user = if (default) userName else "$userName-$accountId"
            PasswordSafeSecrets.clear(CredentialAttributes(service, user), "legacy $serviceName")
        }
    }
}
