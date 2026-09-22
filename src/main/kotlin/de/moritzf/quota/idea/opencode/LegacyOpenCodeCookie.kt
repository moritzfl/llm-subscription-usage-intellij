package de.moritzf.quota.idea.opencode

import com.intellij.credentialStore.CredentialAttributes
import de.moritzf.quota.idea.auth.PasswordSafeSecrets
import de.moritzf.quota.idea.common.QuotaProviderType

/** Removal only: old plugin-owned cookies are no longer used for authentication. */
internal object LegacyOpenCodeCookie {
    fun clear(accountId: String) {
        val default = accountId == QuotaProviderType.OPEN_CODE.id
        val service = if (default) "OpenCode Session Cookie" else "OpenCode Session Cookie ($accountId)"
        val user = if (default) "opencode-session" else "opencode-session-$accountId"
        PasswordSafeSecrets.clear(CredentialAttributes(service, user), "legacy OpenCode session cookie")
    }
}
