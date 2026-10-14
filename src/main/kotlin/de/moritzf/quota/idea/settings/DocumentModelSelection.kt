package de.moritzf.quota.idea.settings

import de.moritzf.quota.azure.azureDocumentSelectionUsesVision
import de.moritzf.quota.idea.common.AzureQuotaProvider
import de.moritzf.quota.idea.common.QuotaBundle.message
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
            QuotaSettingsState.getInstance()
                .account(accountId)
                ?.extra(ProviderAccount.EXTRA_DOCUMENT_MODEL)
        }
            .getOrNull()
        if (saved == DocumentModels.OFF) return DocumentModels.OFF
        return when (type) {
            QuotaProviderType.MISTRAL ->
                DocumentModels.resolveDetected(
                    saved,
                    DocumentModels.MISTRAL_DEFAULT,
                    DocumentModels::isMistralOcrModel,
                )
            QuotaProviderType.ZAI ->
                DocumentModels.resolveDetected(
                    saved,
                    ZaiOcrClient.DEFAULT_MODEL,
                    DocumentModels::isZaiOcrModel,
                )
            QuotaProviderType.OPEN_AI ->
                saved
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        DocumentModels.resolve(
                            it,
                            DocumentModels.openAiVisionModels(OpenAiProxyServer.advertisedModels()),
                            DocumentModels.OPEN_AI_DEFAULT,
                        )
                    } ?: DocumentModels.OFF
            QuotaProviderType.SUPERGROK ->
                saved
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        DocumentModels.resolveDetected(
                            it,
                            DocumentModels.SUPERGROK_DEFAULT,
                            DocumentModels::isSuperGrokDocumentModel,
                        )
                    } ?: DocumentModels.OFF
            QuotaProviderType.GITHUB,
            QuotaProviderType.OPENAI_COMPATIBLE,
            QuotaProviderType.LITELLM,
            QuotaProviderType.OPEN_CODE ->
                saved?.trim()?.takeIf { it.isNotEmpty() } ?: DocumentModels.OFF
            else -> saved?.trim().orEmpty()
        }
    }

    /** Vision and local PDFBox are usable, but worse than a document or OCR model. */
    fun showsConversionWarning(provider: DocumentToMarkdownProvider): Boolean =
        when (provider) {
            DocumentToMarkdownProvider.OPEN_AI,
            DocumentToMarkdownProvider.SUPERGROK,
            DocumentToMarkdownProvider.GITHUB,
            DocumentToMarkdownProvider.OPENAI_COMPATIBLE,
            DocumentToMarkdownProvider.LITELLM,
            DocumentToMarkdownProvider.OPEN_CODE,
            DocumentToMarkdownProvider.PDFBOX -> true
            DocumentToMarkdownProvider.AZURE -> azureVisionSelected()
            else -> false
        }

    fun visionHint(provider: DocumentToMarkdownProvider): String? {
        if (provider == DocumentToMarkdownProvider.AZURE) {
            return if (azureVisionSelected()) message("settings.azure.document.warning") else null
        }
        if (
            provider == DocumentToMarkdownProvider.OPENAI_COMPATIBLE ||
                provider == DocumentToMarkdownProvider.LITELLM ||
                provider == DocumentToMarkdownProvider.GITHUB ||
                provider == DocumentToMarkdownProvider.OPEN_CODE
        ) {
            return message("settings.model.document.warning")
        }
        if (
            provider != DocumentToMarkdownProvider.OPEN_AI &&
                provider != DocumentToMarkdownProvider.SUPERGROK
        )
            return null
        val type = provider.providerType ?: return null
        val model = runCatching {
            val account =
                AccountResolver.resolve(type, capability = AccountCapability.DOCUMENT_TO_MARKDOWN)
            forAccount(type, account.id)
        }
            .getOrNull()
        val warning = message("settings.model.document.warning")
        return if (model.isNullOrBlank()) warning
        else message("settings.model.document.warning.withModel", model, warning)
    }

    private fun azureVisionSelected(): Boolean {
        val selection = runCatching {
            val account =
                AccountResolver.resolve(
                    QuotaProviderType.AZURE,
                    capability = AccountCapability.DOCUMENT_TO_MARKDOWN,
                )
            AzureQuotaProvider.ocrDeploymentForAccount(account.id)
        }
            .getOrNull()
        return azureDocumentSelectionUsesVision(selection)
    }
}
