package de.moritzf.quota.idea.operations

import de.moritzf.quota.azure.AZURE_DOCUMENT_INTELLIGENCE_LAYOUT
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.AzureOcrException
import de.moritzf.quota.azure.azureDocumentSelection
import de.moritzf.quota.azure.azureOcrDeploymentId
import de.moritzf.quota.azure.isAzureCohereSelection
import de.moritzf.quota.idea.common.AzureQuotaProvider
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.mcp.*
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.mistral.MistralQuotaException
import de.moritzf.quota.shared.DocumentImageFormat
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.zai.ZaiQuotaException

internal suspend fun SubscriptionOperations.subscription_document_to_markdown(
    provider: DocumentToMarkdownProvider = DocumentToMarkdownProvider.MISTRAL,
    documentUrl: String? = null,
    localFile: String? = null,
    outputFile: String? = null,
    includeImages: Boolean = true,
    model: String = "",
    pageFrom: Int = 0,
    pageTo: Int = 0,
    imageFormat: DocumentImageFormat = DocumentImageFormat.SVG,
    imageDpi: Int = 300,
    imagePaddingPoints: Double = 2.0,
): String {
    val imageOptions =
        try {
            DocumentImageOptions(imageFormat, imageDpi, imagePaddingPoints)
        } catch (exception: IllegalArgumentException) {
            exception.rethrowIfCancellation()
            return errorResult(exception.message ?: "Invalid image export options.")
        }
    if (
        provider != DocumentToMarkdownProvider.PDFBOX &&
            provider != DocumentToMarkdownProvider.AZURE
    ) {
        val chosen = model.ifBlank { documentModel(provider) }
        if (chosen == "-" || chosen.isBlank()) {
            return errorResult("Document conversion is off. Pick a model in settings.")
        }
    }
    return when (provider) {
        DocumentToMarkdownProvider.AZURE ->
            azureDocumentToMarkdown(
                documentUrl,
                localFile,
                outputFile,
                includeImages,
                model,
                pageFrom,
                pageTo,
                imageOptions,
            )

        DocumentToMarkdownProvider.MISTRAL ->
            mistralDocumentToMarkdown(
                documentUrl,
                localFile,
                outputFile,
                includeImages,
                model.ifBlank { documentModel(DocumentToMarkdownProvider.MISTRAL) },
                imageOptions,
            )

        DocumentToMarkdownProvider.ZAI ->
            zaiDocumentToMarkdown(
                documentUrl,
                localFile,
                outputFile,
                includeImages,
                model.ifBlank { documentModel(DocumentToMarkdownProvider.ZAI) },
                imageOptions,
            )

        DocumentToMarkdownProvider.OPEN_AI ->
            codexResult(
                codexClient(AccountCapability.DOCUMENT_TO_MARKDOWN)
                    .documentToMarkdown(
                        documentUrl,
                        resolveOptionalPath(localFile),
                        resolveOptionalPath(outputFile),
                        includeImages,
                        model.ifBlank { documentModel(DocumentToMarkdownProvider.OPEN_AI) },
                        pageFrom.takeIf { it > 0 },
                        pageTo.takeIf { it > 0 },
                    )
            )

        DocumentToMarkdownProvider.SUPERGROK ->
            superGrokDocumentToMarkdown(
                documentUrl,
                localFile,
                outputFile,
                includeImages,
                model.ifBlank { documentModel(DocumentToMarkdownProvider.SUPERGROK) },
                pageFrom.takeIf { it > 0 },
                pageTo.takeIf { it > 0 },
            )

        DocumentToMarkdownProvider.GITHUB ->
            nativePdfDocument(
                DocumentToMarkdownProvider.GITHUB,
                documentUrl,
                localFile,
                outputFile,
                model,
                pageFrom,
                pageTo,
            )

        DocumentToMarkdownProvider.OPEN_CODE ->
            nativePdfDocument(
                DocumentToMarkdownProvider.OPEN_CODE,
                documentUrl,
                localFile,
                outputFile,
                model,
                pageFrom,
                pageTo,
            )

        DocumentToMarkdownProvider.PDFBOX ->
            pdfBoxDocumentToMarkdown(
                documentUrl,
                localFile,
                outputFile,
                includeImages,
                pageFrom.takeIf { it > 0 },
                pageTo.takeIf { it > 0 },
            )
    }
}

internal suspend fun SubscriptionOperations.subscription_svg_to_png(
    localFile: String,
    outputFile: String? = null,
    dpi: Int = 300,
): String {
    val source =
        resolveOptionalPath(localFile) ?: return errorResult("Pass a local SVG path in localFile.")
    return try {
        de.moritzf.quota.shared.SvgRasterizer.toPng(
            source,
            resolveOptionalPath(outputFile),
            dpi,
        )
    } catch (exception: kotlinx.coroutines.CancellationException) {
        exception.rethrowIfCancellation()
        throw exception
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "SVG rasterization failed.")
    }
}

internal suspend fun SubscriptionOperations.superGrokDocumentToMarkdown(
    documentUrl: String?,
    localFile: String?,
    outputFile: String?,
    includeImages: Boolean,
    model: String,
    pageFrom: Int? = null,
    pageTo: Int? = null,
): String {
    return withSuperGrokAuth(
        "Grok document conversion failed.",
        AccountCapability.DOCUMENT_TO_MARKDOWN,
    ) { accessToken ->
        superGrokDocumentClient.convertDocument(
            accessToken = accessToken,
            documentUrl = documentUrl,
            localFile = resolveOptionalPath(localFile),
            outputFile = resolveOptionalPath(outputFile),
            includeImages = includeImages,
            model = model,
            pageFrom = pageFrom,
            pageTo = pageTo,
        )
    }
}

internal suspend fun SubscriptionOperations.mistralDocumentToMarkdown(
    documentUrl: String?,
    localFile: String?,
    outputFile: String?,
    includeImages: Boolean,
    model: String,
    imageOptions: DocumentImageOptions,
): String {
    val apiKey =
        resolvedApiKey(
            QuotaProviderType.MISTRAL,
            AccountCapability.DOCUMENT_TO_MARKDOWN,
        ) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
    }
    return try {
        mistralOcrClient.convertDocument(
            apiKey = apiKey,
            documentUrl = documentUrl,
            localFile = resolveOptionalPath(localFile),
            outputFile = resolveOptionalPath(outputFile),
            includeImages = includeImages,
            model = model,
            imageOptions = imageOptions,
        )
    } catch (exception: MistralQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral OCR failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral OCR failed.")
    }
}

internal suspend fun SubscriptionOperations.azureDocumentToMarkdown(
    documentUrl: String?,
    localFile: String?,
    outputFile: String?,
    includeImages: Boolean,
    model: String,
    pageFrom: Int,
    pageTo: Int,
    imageOptions: DocumentImageOptions,
): String {
    val accountId =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    QuotaProviderType.AZURE,
                    capability = AccountCapability.DOCUMENT_TO_MARKDOWN,
                )
                .id
        } catch (_: de.moritzf.quota.idea.settings.AccountResolveException) {
            return errorResult("Add an Azure account in settings to use OCR.")
        }
    val deployment =
        azureDocumentSelection(model, AzureQuotaProvider.ocrDeploymentForAccount(accountId))
            ?: return errorResult(
                "Select an Azure document model in settings, or pass model. '-' disables the settings default."
            )
    val deploymentId = azureOcrDeploymentId(deployment)
    if (
        de.moritzf.quota.azure.isAzureNativePdfSelection(deployment) && (pageFrom > 0 || pageTo > 0)
    ) {
        return errorResult("pageFrom/pageTo are not supported for Azure native PDF.")
    }
    if (
        deployment != AZURE_DOCUMENT_INTELLIGENCE_LAYOUT &&
            !isAzureCohereSelection(deployment) &&
            !de.moritzf.quota.azure.isAzureNativePdfSelection(deployment) &&
            (pageFrom > 0 || pageTo > 0)
    )
        return errorResult("pageFrom/pageTo are not supported for Azure Mistral OCR.")
    if (deployment == AZURE_DOCUMENT_INTELLIGENCE_LAYOUT && (pageFrom > 0 || pageTo > 0)) {
        return errorResult("pageFrom/pageTo are not supported for Azure Document Intelligence.")
    }
    val executable =
        AzureQuotaProvider.executableForAccount(accountId)
            ?: return errorResult("Azure CLI not found. Set its path in Azure settings.")
    return try {
        val cli = AzureCli(executable)
        val config = AzureQuotaProvider.configForAccount(accountId)
        val sourceFile = resolveOptionalPath(localFile)
        val destination = resolveOptionalPath(outputFile)
        when {
            de.moritzf.quota.azure.isAzureNativePdfSelection(deployment) -> {
                val source =
                    sourceFile
                        ?: return errorResult("Azure native PDF conversion needs a local PDF.")
                val nativeOutput =
                    destination
                        ?: de.moritzf.quota.shared.DocumentMarkdown.defaultOutput(source)
                        ?: return errorResult("Azure native PDF conversion needs an output path.")
                de.moritzf.quota.idea.action.NativeDocumentConversion.azure(
                    accountId,
                    deployment,
                    source,
                    nativeOutput,
                )
            }
            deployment == AZURE_DOCUMENT_INTELLIGENCE_LAYOUT ->
                azureDocumentIntelligenceClient.convertDocument(
                    cli,
                    config,
                    documentUrl,
                    sourceFile,
                    destination,
                    includeImages,
                    imageOptions,
                )
            isAzureCohereSelection(deployment) ->
                azureCohereParseClient.convertDocument(
                    cli,
                    config,
                    deploymentId,
                    documentUrl,
                    sourceFile,
                    destination,
                    includeImages,
                    pageFrom.takeIf { it > 0 },
                    pageTo.takeIf { it > 0 },
                    imageOptions,
                )
            else ->
                azureOcrClient.convertDocument(
                    cli,
                    config,
                    deploymentId,
                    documentUrl,
                    sourceFile,
                    destination,
                    includeImages,
                    imageOptions = imageOptions,
                )
        }
    } catch (exception: AzureOcrException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Azure OCR failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Azure OCR failed.")
    }
}

internal suspend fun SubscriptionOperations.zaiDocumentToMarkdown(
    documentUrl: String?,
    localFile: String?,
    outputFile: String?,
    includeImages: Boolean,
    model: String,
    imageOptions: DocumentImageOptions,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.DOCUMENT_TO_MARKDOWN) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
    }
    return try {
        zaiOcrClient.convertDocument(
            apiKey = apiKey,
            documentUrl = documentUrl,
            localFile = resolveOptionalPath(localFile),
            outputFile = resolveOptionalPath(outputFile),
            includeImages = includeImages,
            model = model,
            imageOptions = imageOptions,
        )
    } catch (exception: ZaiQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai OCR failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai OCR failed.")
    }
}

internal suspend fun SubscriptionOperations.nativePdfDocument(
    provider: DocumentToMarkdownProvider,
    documentUrl: String?,
    localFile: String?,
    outputFile: String?,
    model: String,
    pageFrom: Int,
    pageTo: Int,
): String {
    if (pageFrom > 0 || pageTo > 0)
        return errorResult("pageFrom/pageTo are not supported for native PDF.")
    if (!documentUrl.isNullOrBlank())
        return errorResult("This provider needs a local PDF in localFile.")
    val source =
        resolveOptionalPath(localFile) ?: return errorResult("Pass a local PDF path in localFile.")
    val output =
        resolveOptionalPath(outputFile)
            ?: de.moritzf.quota.shared.DocumentMarkdown.defaultOutput(source)
            ?: return errorResult("Could not choose an output path.")
    val type = provider.providerType ?: return errorResult("Unknown document provider.")
    val account =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    type,
                    capability = AccountCapability.DOCUMENT_TO_MARKDOWN,
                )
                .id
        } catch (_: de.moritzf.quota.idea.settings.AccountResolveException) {
            return errorResult("Sign in to ${type.displayName} in settings.")
        }
    val selected = model.ifBlank { documentModel(provider) }
    return try {
        when (provider) {
            DocumentToMarkdownProvider.GITHUB ->
                de.moritzf.quota.idea.action.NativeDocumentConversion.github(
                    account,
                    selected,
                    source,
                    output,
                )
            DocumentToMarkdownProvider.OPEN_CODE ->
                de.moritzf.quota.idea.action.NativeDocumentConversion.openCode(
                    account,
                    selected,
                    source,
                    output,
                )
            else -> errorResult("Unsupported native PDF provider.")
        }
    } catch (exception: kotlinx.coroutines.CancellationException) {
        exception.rethrowIfCancellation()
        throw exception
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Document conversion failed.")
    }
}

internal suspend fun SubscriptionOperations.pdfBoxDocumentToMarkdown(
    documentUrl: String?,
    localFile: String?,
    outputFile: String?,
    includeImages: Boolean,
    pageFrom: Int?,
    pageTo: Int?,
): String {
    if (!documentUrl.isNullOrBlank()) {
        return errorResult(
            "PDFBox only reads a local PDF. Pass localFile, or use an OCR provider for a URL."
        )
    }
    val source =
        resolveOptionalPath(localFile)
            ?: return errorResult("PDFBox needs a local PDF path in localFile.")
    return try {
        de.moritzf.quota.openai.proxy.pdf.PdfBoxMarkdown.convert(
            source,
            resolveOptionalPath(outputFile),
            includeImages,
            pageFrom,
            pageTo,
        )
    } catch (exception: kotlinx.coroutines.CancellationException) {
        exception.rethrowIfCancellation()
        throw exception
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "PDFBox text extraction failed.")
    }
}

internal fun SubscriptionOperations.documentModel(provider: DocumentToMarkdownProvider): String {
    val type = provider.providerType ?: return ""
    val accountId =
        runCatching {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    type,
                    capability =
                        de.moritzf.quota.idea.settings.AccountCapability.DOCUMENT_TO_MARKDOWN,
                )
                .id
        }
            .getOrNull() ?: return ""
    return de.moritzf.quota.idea.settings.DocumentModelSelection.forAccount(type, accountId)
}
