package de.moritzf.quota.idea.settings

import de.moritzf.quota.azure.azureDocumentSelectionUsesVision
import de.moritzf.quota.idea.common.AzureQuotaProvider
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.zai.ZaiOcrClient

internal object DocumentModelSelection {
    fun forAccount(type: QuotaProviderType, accountId: String, explicit: String = ""): String {
        val requested = explicit.trim()
        if (requested == DocumentModels.OFF) return DocumentModels.OFF
        if (requested.isNotEmpty()) return requested
        val saved = runCatching {
            QuotaSettingsState.getInstance().account(accountId)?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)
        }.getOrNull()
        if (saved == DocumentModels.OFF) return DocumentModels.OFF
        return when (type) {
            QuotaProviderType.MISTRAL ->
                DocumentModels.resolveDetected(saved, DocumentModels.MISTRAL_DEFAULT, DocumentModels::isMistralOcrModel)
            QuotaProviderType.ZAI ->
                DocumentModels.resolveDetected(saved, ZaiOcrClient.DEFAULT_MODEL, DocumentModels::isZaiOcrModel)
            QuotaProviderType.OPEN_AI ->
                saved?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    DocumentModels.resolve(it, DocumentModels.openAiVisionModels(OpenAiProxyServer.advertisedModels()), DocumentModels.OPEN_AI_DEFAULT)
                } ?: DocumentModels.OFF
            QuotaProviderType.SUPERGROK ->
                saved?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    DocumentModels.resolveDetected(it, DocumentModels.SUPERGROK_DEFAULT, DocumentModels::isSuperGrokDocumentModel)
                } ?: DocumentModels.OFF
            QuotaProviderType.GITHUB, QuotaProviderType.OPEN_CODE ->
                saved?.trim()?.takeIf { it.isNotEmpty() } ?: DocumentModels.OFF
            else -> saved?.trim().orEmpty()
        }
    }

    /** Vision and local PDFBox are usable, but worse than a document or OCR model. */
    fun showsConversionWarning(provider: DocumentToMarkdownProvider): Boolean = when (provider) {
        DocumentToMarkdownProvider.OPEN_AI, DocumentToMarkdownProvider.SUPERGROK,
        DocumentToMarkdownProvider.GITHUB, DocumentToMarkdownProvider.OPEN_CODE,
        DocumentToMarkdownProvider.PDFBOX -> true
        DocumentToMarkdownProvider.AZURE -> azureVisionSelected()
        else -> false
    }

    fun visionHint(provider: DocumentToMarkdownProvider): String? {
        if (provider == DocumentToMarkdownProvider.AZURE) {
            return if (azureVisionSelected()) DocumentModels.AZURE_VISION_WARNING else null
        }
        if (provider == DocumentToMarkdownProvider.GITHUB || provider == DocumentToMarkdownProvider.OPEN_CODE) {
            return DocumentModels.VISION_WARNING
        }
        if (provider != DocumentToMarkdownProvider.OPEN_AI && provider != DocumentToMarkdownProvider.SUPERGROK) return null
        val type = provider.providerType ?: return null
        val model = runCatching {
            val account = AccountResolver.resolve(type, capability = AccountCapability.DOCUMENT_TO_MARKDOWN)
            forAccount(type, account.id)
        }.getOrNull()
        return if (model.isNullOrBlank()) DocumentModels.VISION_WARNING else "Uses $model.\n\n${DocumentModels.VISION_WARNING}"
    }

    private fun azureVisionSelected(): Boolean {
        val selection = runCatching {
            val account = AccountResolver.resolve(QuotaProviderType.AZURE, capability = AccountCapability.DOCUMENT_TO_MARKDOWN)
            AzureQuotaProvider.ocrDeploymentForAccount(account.id)
        }.getOrNull()
        return azureDocumentSelectionUsesVision(selection)
    }
}
