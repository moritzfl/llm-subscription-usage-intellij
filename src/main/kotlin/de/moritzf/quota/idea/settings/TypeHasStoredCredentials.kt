package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.auth.OAuthCredentialsStore
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.cursor.CursorCredentialsStore
import de.moritzf.quota.idea.github.GitHubCredentialsStore
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.minimax.MiniMaxApiKeyStore
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.mistral.MistralSessionCookieStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.opencode.OpenCodeAuthService
import de.moritzf.quota.idea.zai.ZaiApiKeyStore

internal object TypeHasStoredCredentials {
    operator fun invoke(type: QuotaProviderType): Boolean = probe(type)

    private fun probe(type: QuotaProviderType): Boolean {
        return when (type) {
            QuotaProviderType.ANTIGRAVITY -> false // Opt-in only; AGY owns its credentials.
            QuotaProviderType.AZURE -> false // Opt-in only; Azure CLI owns its credentials.
            QuotaProviderType.CLAUDE,
            QuotaProviderType.OPEN_AI,
            QuotaProviderType.SUPERGROK,
            -> hasOAuth(type)
            QuotaProviderType.CURSOR ->
                !CursorCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            QuotaProviderType.GITHUB ->
                !GitHubCredentialsStore.getInstance().loadBlocking()?.accessToken.isNullOrBlank()
            QuotaProviderType.KIMI ->
                KimiCredentialsStore.getInstance().loadBlocking()?.isUsable() == true
            QuotaProviderType.MINIMAX ->
                !MiniMaxApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.MISTRAL ->
                !MistralSessionCookieStore.getInstance().loadBlocking().isNullOrBlank() ||
                    !MistralApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.OLLAMA ->
                !OllamaApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
            QuotaProviderType.OPEN_CODE ->
                OpenCodeAuthService.getInstance().loadBlocking(type.id) != null
            QuotaProviderType.ZAI ->
                !ZaiApiKeyStore.getInstance().loadBlocking().isNullOrBlank()
        }
    }

    private fun hasOAuth(type: QuotaProviderType): Boolean {
        return OAuthCredentialsStore.forProvider(type).load() != null
    }
}
