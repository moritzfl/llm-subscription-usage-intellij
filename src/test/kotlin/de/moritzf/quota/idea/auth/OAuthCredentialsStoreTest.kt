package de.moritzf.quota.idea.auth

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun currentReadFailureDoesNotFallbackToOrMigrateLegacyCredentials() {
        val currentService = OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.CLAUDE)
        val legacyService = "LLM Subscription Usage OAuth"
        var writes = 0
        val store = OAuthCredentialsStore(
            serviceName = currentService,
            userName = "claude-oauth",
            legacyServiceName = legacyService,
            legacyUserName = "claude-oauth",
            credentialReader = { attributes ->
                if (attributes.serviceName == currentService) {
                    throw IllegalStateException("Password Safe unavailable")
                }
                Credentials("claude-oauth", credentialsJson("legacy-token"))
            },
            credentialWriter = { _, _ -> writes++ },
            coordinator = OAuthCredentialCoordinator(),
        )

        assertFailsWith<IllegalStateException> { store.load() }
        assertEquals(0, writes)
    }

    @Test
    fun clearFailureIsReported() {
        val store = OAuthCredentialsStore(
            serviceName = OAuthCredentialsStore.serviceNameForProvider(QuotaProviderType.CLAUDE),
            userName = "claude-oauth",
            credentialWriter = { _, _ -> throw IllegalStateException("Password Safe unavailable") },
            coordinator = OAuthCredentialCoordinator(),
        )

        assertFailsWith<IllegalStateException> { store.clear() }
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
            coordinator = OAuthCredentialCoordinator(),
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
