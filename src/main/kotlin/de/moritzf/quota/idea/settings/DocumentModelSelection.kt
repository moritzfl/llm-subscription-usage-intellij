package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.zai.ZaiOcrClient

internal object DocumentModelSelection {
    fun forAccount(type: QuotaProviderType, accountId: String, explicit: String = ""): String {
        val requested = explicit.trim()
        if (requested.isNotEmpty()) return requested
        val saved = runCatching {
            QuotaSettingsState.getInstance().account(accountId)?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)
        }.getOrNull()
        return when (type) {
            QuotaProviderType.MISTRAL ->
                DocumentModels.resolveDetected(saved, DocumentModels.MISTRAL_DEFAULT, DocumentModels::isMistralOcrModel)
            QuotaProviderType.ZAI ->
                DocumentModels.resolveDetected(saved, ZaiOcrClient.DEFAULT_MODEL, DocumentModels::isZaiOcrModel)
            QuotaProviderType.OPEN_AI ->
                DocumentModels.resolve(saved, DocumentModels.openAiVisionModels(OpenAiProxyServer.advertisedModels()), DocumentModels.OPEN_AI_DEFAULT)
            QuotaProviderType.SUPERGROK ->
                DocumentModels.resolveDetected(saved, DocumentModels.SUPERGROK_DEFAULT, DocumentModels::isSuperGrokDocumentModel)
            else -> saved?.trim().orEmpty()
        }
    }

    fun visionHint(provider: DocumentToMarkdownProvider): String? {
        if (provider != DocumentToMarkdownProvider.OPEN_AI && provider != DocumentToMarkdownProvider.SUPERGROK) return null
        val type = provider.providerType ?: return null
        val model = runCatching {
            val account = AccountResolver.resolve(type, capability = AccountCapability.DOCUMENT_TO_MARKDOWN)
            forAccount(type, account.id)
        }.getOrNull()
        return if (model.isNullOrBlank()) DocumentModels.VISION_WARNING else "Uses $model. ${DocumentModels.VISION_WARNING}"
    }
}
