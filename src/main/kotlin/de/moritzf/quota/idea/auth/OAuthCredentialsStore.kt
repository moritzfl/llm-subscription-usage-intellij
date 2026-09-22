package de.moritzf.quota.idea.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import de.moritzf.quota.idea.common.CredentialStorage
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport

/**
 * Handles loading, saving, and clearing OAuth credentials in PasswordSafe.
 */
class OAuthCredentialsStore(
    serviceName: String,
    private val userName: String,
    legacyServiceName: String? = null,
    legacyUserName: String? = null,
    private val credentialReader: (CredentialAttributes) -> Credentials? = { PasswordSafe.instance.get(it) },
    private val credentialWriter: (CredentialAttributes, Credentials?) -> Unit = { attributes, credentials ->
        PasswordSafe.instance.set(attributes, credentials)
    },
    override val coordinator: OAuthCredentialCoordinator = OAuthCredentialCoordinator(
        PathManager.getCommonDataPath().resolve("llm-subscription-usage/oauth")
            .resolve(OAuthCredentialCoordinator.hash(serviceName)),
    ),
) : OAuthCredentialStore {
    private val attributes = CredentialAttributes(serviceName, userName)
    private val legacyAttributes = if (legacyServiceName != null && legacyUserName != null) {
        CredentialAttributes(legacyServiceName, legacyUserName)
    } else {
        null
    }

    override fun load(): OAuthCredentials? {
        val current = loadStoredCredentials(attributes, "stored")
        if (current.present) {
            return current.credentials
        }

        val legacy = legacyAttributes?.let { loadStoredCredentials(it, "legacy") } ?: return null
        return legacy.credentials
    }

    override fun save(credentials: OAuthCredentials) = coordinator.withWriteLock {
        val json = JsonSupport.json.encodeToString(credentials)
        try {
            credentialWriter(attributes, Credentials(userName, json))
            coordinator.recordWrite()
            LOG.info(
                "Saved OAuth credentials for $userName (access=${QuotaTokenUtil.fingerprint(credentials.accessToken)}," +
                    " refresh=${QuotaTokenUtil.fingerprint(credentials.refreshToken)}," +
                    " storage=${CredentialStorage.describe()})"
            )
        } catch (exception: Exception) {
            LOG.warn("Failed to save OAuth credentials", exception)
            throw IllegalStateException("Could not persist OAuth credentials", exception)
        }
    }

    override fun clear() = coordinator.withWriteLock {
        try {
            credentialWriter(attributes, Credentials(userName, CLEARED_MARKER))
            coordinator.recordWrite()
        } catch (exception: Exception) {
            LOG.warn("Failed to clear stored OAuth credentials", exception)
            throw IllegalStateException("Could not clear OAuth credentials", exception)
        }
    }

    private fun loadStoredCredentials(attributes: CredentialAttributes, source: String): StoredCredentials {
        val stored = try {
            credentialReader(attributes)
        } catch (exception: Exception) {
            LOG.warn("Failed to load $source OAuth credentials", exception)
            throw IllegalStateException("Could not load $source OAuth credentials", exception)
        } ?: return StoredCredentials(present = false, credentials = null)
        val json = stored.getPasswordAsString()
        if (json.isNullOrBlank() || json == CLEARED_MARKER) {
            return StoredCredentials(present = true, credentials = null)
        }

        return try {
            StoredCredentials(present = true, credentials = JsonSupport.json.decodeFromString<OAuthCredentials>(json))
        } catch (exception: Exception) {
            LOG.warn("Failed to parse $source OAuth credentials", exception)
            throw IllegalStateException("Could not parse $source OAuth credentials", exception)
        }
    }

    private data class StoredCredentials(val present: Boolean, val credentials: OAuthCredentials?)

    companion object {
        private const val LEGACY_SERVICE_NAME = "LLM Subscription Usage OAuth"
        private const val CLEARED_MARKER = "__llm_subscription_usage_oauth_cleared__"
        private val LOG = Logger.getInstance(OAuthCredentialsStore::class.java)

        fun forProvider(type: QuotaProviderType): OAuthCredentialsStore = forAccount(type.id, type)

        fun forAccount(accountId: String, type: QuotaProviderType): OAuthCredentialsStore {
            val userName = "$accountId-oauth"
            val migrated = accountId == type.id
            return OAuthCredentialsStore(
                serviceName = serviceNameForAccount(accountId),
                userName = userName,
                legacyServiceName = if (migrated) LEGACY_SERVICE_NAME else null,
                legacyUserName = if (migrated) userName else null,
            )
        }

        internal fun serviceNameForProvider(type: QuotaProviderType): String = serviceNameForAccount(type.id)

        internal fun serviceNameForAccount(accountId: String): String = "$LEGACY_SERVICE_NAME ($accountId)"
    }
}
