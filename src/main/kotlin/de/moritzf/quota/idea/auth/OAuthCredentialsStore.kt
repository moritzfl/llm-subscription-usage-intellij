package de.moritzf.quota.idea.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.SlowOperations
import de.moritzf.quota.JsonSupport

/**
 * Handles loading, saving, and clearing OAuth credentials in PasswordSafe.
 */
class OAuthCredentialsStore(serviceName: String, private val userName: String) : OAuthCredentialStore {
    private val attributes = CredentialAttributes(serviceName, userName)

    override fun load(): OAuthCredentials? {
        val stored = try {
            withPasswordSafe { PasswordSafe.instance.get(attributes) }
        } catch (exception: Exception) {
            LOG.warn("Failed to load stored OAuth credentials", exception)
            return null
        } ?: return null
        val json = stored.getPasswordAsString() ?: return null
        if (json.isBlank()) {
            return null
        }

        return try {
            JsonSupport.json.decodeFromString<OAuthCredentials>(json)
        } catch (exception: Exception) {
            LOG.warn("Failed to parse stored credentials", exception)
            null
        }
    }

    override fun save(credentials: OAuthCredentials) {
        val json = JsonSupport.json.encodeToString(credentials)
        try {
            withPasswordSafe {
                PasswordSafe.instance.set(attributes, Credentials(userName, json))
            }
        } catch (exception: Exception) {
            LOG.warn("Failed to save OAuth credentials", exception)
            throw IllegalStateException("Could not persist OAuth credentials", exception)
        }
    }

    override fun clear() {
        try {
            withPasswordSafe {
                PasswordSafe.instance.set(attributes, null)
            }
        } catch (exception: Exception) {
            LOG.warn("Failed to clear stored OAuth credentials", exception)
        }
    }

    private fun <T> withPasswordSafe(action: () -> T): T {
        return SlowOperations.knownIssue("IDEA-352594").use {
            action()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(OAuthCredentialsStore::class.java)
    }
}
