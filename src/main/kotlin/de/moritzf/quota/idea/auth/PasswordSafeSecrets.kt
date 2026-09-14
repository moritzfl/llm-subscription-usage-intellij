package de.moritzf.quota.idea.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

internal object PasswordSafeSecrets {
    fun clear(attributes: CredentialAttributes, label: String) {
        try {
            PasswordSafe.instance.set(attributes, null)
        } catch (exception: Exception) {
            throw IllegalStateException("Could not clear $label", exception)
        }
    }
}
