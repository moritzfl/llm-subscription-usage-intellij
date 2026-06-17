package de.moritzf.quota.idea.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class OAuthCredentialsStoreTest {
    @Test
    fun providerServiceNamesAreDistinct() {
        assertNotEquals(
            OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.OPEN_AI),
            OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.SUPERGROK),
        )
    }

    @Test
    fun clearingSuperGrokDoesNotDeleteOpenAiOnServiceNameOnlyBackend() {
        val passwordSafe = FakePasswordSafe()
        val openAiStore = storeFor(QuotaProviderType.OPEN_AI, passwordSafe)
        val superGrokStore = storeFor(QuotaProviderType.SUPERGROK, passwordSafe)

        openAiStore.save(credentials("openai-token"))
        superGrokStore.save(credentials("grok-token"))
        superGrokStore.clear()

        assertEquals("openai-token", openAiStore.load()?.accessToken)
        assertNull(superGrokStore.load())
    }

    @Test
    fun clearSuppressesLegacyCredentialFallback() {
        val passwordSafe = FakePasswordSafe()
        val superGrokStore = storeFor(QuotaProviderType.SUPERGROK, passwordSafe)
        passwordSafe.set(
            CredentialAttributes("LLM Subscription Usage OAuth", "supergrok-oauth"),
            Credentials("supergrok-oauth", credentialsJson("legacy-grok-token")),
        )

        assertEquals("legacy-grok-token", superGrokStore.load()?.accessToken)

        superGrokStore.clear()

        assertNull(superGrokStore.load())
    }

    private fun storeFor(type: QuotaProviderType, passwordSafe: FakePasswordSafe): OAuthCredentialsStore {
        val userName = "${type.id}-oauth"
        return OAuthCredentialsStore(
            serviceName = OAuthCredentialsStore.serviceNameForProvider(type),
            userName = userName,
            legacyServiceName = "LLM Subscription Usage OAuth",
            legacyUserName = userName,
            credentialReader = passwordSafe::get,
            credentialWriter = passwordSafe::set,
        )
    }

    private fun credentials(accessToken: String): OAuthCredentials {
        return OAuthCredentials(
            accessToken = accessToken,
            refreshToken = "$accessToken-refresh",
            expiresAt = System.currentTimeMillis() + 10 * 60_000,
            accountId = "$accessToken-account",
        )
    }

    private fun credentialsJson(accessToken: String): String {
        return JsonSupport.json.encodeToString(OAuthCredentials.serializer(), credentials(accessToken))
    }

    private class FakePasswordSafe {
        private val stored = LinkedHashMap<String, Credentials>()

        fun get(attributes: CredentialAttributes): Credentials? = stored[attributes.serviceName]

        fun set(attributes: CredentialAttributes, credentials: Credentials?) {
            if (credentials == null) {
                stored.remove(attributes.serviceName)
            } else {
                stored[attributes.serviceName] = credentials
            }
        }
    }
}
