package de.moritzf.quota.idea.common

import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * State of the IDE credential store that every provider of this plugin writes its logins, session
 * cookies, and API keys to.
 */
object CredentialStorage {
    const val MEMORY_ONLY_WARNING: String =
        "IDE password storage is set to forget passwords after restart: every login, session" +
            " cookie, and API key is lost when the IDE closes. Change it in" +
            " Settings | Appearance & Behavior | System Settings | Passwords."

    /**
     * True when the credential store keeps secrets in memory only: either the user selected "Do not
     * save, forget passwords after restart", or the keychain/KeePass backend failed to open. Both
     * look to the user like every provider revoked its login on restart.
     */
    @JvmStatic
    fun isMemoryOnly(): Boolean = runCatching {
        PasswordSafe.instance.isMemoryOnly
    }
        .getOrDefault(false)

    @JvmStatic fun describe(): String = if (isMemoryOnly()) "memory-only" else "persistent"
}
