package de.moritzf.quota.azure

import java.net.URI

internal fun isAzureOcrModel(name: String): Boolean {
    val model = name.lowercase()
    return model.startsWith("mistral-ocr-") || model.startsWith("mistral-document-ai-") ||
            model.startsWith("cohere-parse-")
}

internal const val AZURE_DOCUMENT_INTELLIGENCE_LAYOUT = "doc-intelligence/prebuilt-layout"
internal const val AZURE_NATIVE_PDF_PREFIX = "native:"
private const val COHERE_SELECTION_PREFIX = "cohere:"

internal fun isAzureCohereParseModel(name: String): Boolean = name.startsWith("cohere-parse-", ignoreCase = true)
internal fun isAzureCohereSelection(selection: String): Boolean = selection.startsWith(COHERE_SELECTION_PREFIX)
internal fun isAzureNativePdfSelection(selection: String): Boolean = selection.startsWith(AZURE_NATIVE_PDF_PREFIX)
internal fun azureNativePdfDeploymentId(selection: String): String = selection.removePrefix(AZURE_NATIVE_PDF_PREFIX)
internal fun azureOcrDeploymentId(selection: String): String = selection.removePrefix(COHERE_SELECTION_PREFIX)

/**
 * Blank [explicit] keeps the settings selection. A passed value uses the same routes:
 * Document Intelligence, `cohere:<deployment>`, or a Mistral OCR deployment name.
 */
internal fun azureDocumentSelection(explicit: String?, settingsSelection: String?): String? {
    val requested = explicit?.trim().orEmpty()
    if (requested.isEmpty()) return settingsSelection?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        requested == AZURE_DOCUMENT_INTELLIGENCE_LAYOUT || requested.equals("prebuilt-layout", ignoreCase = true) ->
            AZURE_DOCUMENT_INTELLIGENCE_LAYOUT
        isAzureNativePdfSelection(requested) -> requested
        isAzureCohereSelection(requested) -> requested
        isAzureCohereParseModel(requested) -> "$COHERE_SELECTION_PREFIX$requested"
        else -> requested
    }
}

/** Deployment IDs can differ from model IDs; ARM provides the underlying model name. */
internal fun azureOcrDeployments(
    quota: AzureQuota?,
    configuredDeployments: String?,
    selectedDeployment: String?,
    resourceName: String?,
    documentIntelligenceAvailable: Boolean = false,
): List<String> {
    val deployments = quota?.windows.orEmpty()
        .filter {
            it.kind == AzureUsageWindow.DEPLOYMENT &&
                    (resourceName == null || it.resourceName.equals(resourceName, ignoreCase = true))
        }
    val modelByDeployment = deployments.associate { it.id to it.modelName }
    fun candidate(id: String, model: String): String =
        if (isAzureCohereParseModel(model)) "$COHERE_SELECTION_PREFIX$id" else id

    val available = deployments.filter { it.modelName?.let(::isAzureOcrModel) == true }
        .map { candidate(it.id, it.modelName!!) } +
            quota?.models.orEmpty().filter { id ->
                modelByDeployment.entries.none { it.key.equals(id, ignoreCase = true) } && isAzureOcrModel(id)
            }.map { candidate(it, it) } +
            configuredDeployments.orEmpty().split(',', ' ', '\n').filter { id ->
                isAzureOcrModel(id) && modelByDeployment.entries.none {
                    it.key.equals(id, ignoreCase = true) && it.value?.let(::isAzureOcrModel) == false
                }
            }.map { id ->
                candidate(
                    id,
                    modelByDeployment.entries.firstOrNull { it.key.equals(id, ignoreCase = true) }?.value ?: id
                )
            } +
            listOfNotNull(selectedDeployment?.takeIf { id ->
                AZURE_DEPLOYMENT_NAME.matches(azureOcrDeploymentId(id)) && modelByDeployment.entries.none {
                    it.key.equals(
                        azureOcrDeploymentId(id),
                        ignoreCase = true
                    ) && it.value?.let(::isAzureOcrModel) == false
                }
            })
    return available.filter { AZURE_DEPLOYMENT_NAME.matches(azureOcrDeploymentId(it)) }.distinct()
        .sortedWith(compareByDescending<String> { azureDocumentModelRank(modelNameForSelection(it, quota)) }.thenBy { it }) +
            listOfNotNull(AZURE_DOCUMENT_INTELLIGENCE_LAYOUT.takeIf { documentIntelligenceAvailable })
}

/**
 * Every other deployment on the resource. Azure does not say which chat models accept PDF,
 * so the user picks. These are never auto-selected.
 */
internal fun azureNativePdfChoices(quota: AzureQuota?, resourceName: String?): List<String> {
    return quota?.windows.orEmpty()
        .filter {
            it.kind == AzureUsageWindow.DEPLOYMENT &&
                (resourceName == null || it.resourceName.equals(resourceName, ignoreCase = true)) &&
                it.modelName?.let(::isAzureOcrModel) != true
        }
        .map { it.id }
        .filter { AZURE_DEPLOYMENT_NAME.matches(it) }
        .distinct()
        .sorted()
        .map { "$AZURE_NATIVE_PDF_PREFIX$it" }
}

/**
 * Best deployment for a first-time resource. Mistral OCR beats Document AI and Cohere.
 * Newer `mistral-ocr-*` ids beat older ones. Document Intelligence is not auto-selected.
 */
internal fun preferredAzureOcrSelection(
    quota: AzureQuota?,
    configuredDeployments: String?,
    resourceName: String?,
): String? {
    val choices = azureOcrDeployments(quota, configuredDeployments, null, resourceName, documentIntelligenceAvailable = false)
    fun isListedDeployment(selection: String): Boolean {
        val id = azureOcrDeploymentId(selection)
        return quota?.windows.orEmpty().any {
            it.kind == AzureUsageWindow.DEPLOYMENT && it.id.equals(id, ignoreCase = true)
        }
    }
    return choices.filter { azureDocumentModelRank(modelNameForSelection(it, quota)) >= 0 }
        .maxWithOrNull(
            compareBy<String> { azureDocumentModelRank(modelNameForSelection(it, quota)) }
                .thenBy { if (isListedDeployment(it)) 1 else 0 }
                .thenBy { it },
        )
}

internal fun azureDocumentModelRank(model: String): Long {
    val name = model.lowercase()
    val family = when {
        name.startsWith("mistral-ocr-") -> 3L
        name.startsWith("mistral-document-ai-") -> 2L
        isAzureCohereParseModel(name) -> 1L
        else -> return -1
    }
    return family * 1_000_000_000L + azureModelVersionScore(name)
}

private fun modelNameForSelection(selection: String, quota: AzureQuota?): String {
    val id = azureOcrDeploymentId(selection)
    val named = quota?.windows.orEmpty().firstOrNull {
        it.kind == AzureUsageWindow.DEPLOYMENT && it.id.equals(id, ignoreCase = true)
    }?.modelName
    return named ?: id
}

private fun azureModelVersionScore(name: String): Long {
    if (name.contains("latest")) return 900_000_000L
    val numbers = Regex("\\d+").findAll(name.substringAfter('-')).map { it.value.toLong() }.toList()
    if (numbers.isEmpty()) return 0L
    val major = numbers.first()
    val minor = numbers.getOrNull(1) ?: 0L
    // 4-1 is a generation. 2505/2512 are YYMM ids from older OCR releases.
    return if (major in 1..99) 500_000_000L + major * 1_000L + minor else major
}

/** OCR is a Foundry provider route, independent of the OpenAI v1 chat endpoint. */
internal fun azureOcrUri(config: AzureAccountConfig): URI? {
    val resource = azureDocumentResource(config) ?: return null
    return URI.create("https://$resource.services.ai.azure.com/providers/mistral/azure/ocr?api-version=2024-05-01-preview")
}

internal fun azureCohereParseUri(config: AzureAccountConfig): URI? {
    val resource = azureDocumentResource(config) ?: return null
    return URI.create("https://$resource.services.ai.azure.com/providers/cohere/v2/parse")
}

internal fun azureDocumentIntelligenceUri(config: AzureAccountConfig): URI? {
    val resource = azureDocumentResource(config) ?: return null
    return URI.create(
        "https://$resource.cognitiveservices.azure.com/documentintelligence/documentModels/prebuilt-layout:analyze" +
                "?_overload=analyzeDocument&api-version=2024-11-30&outputContentFormat=markdown"
    )
}

private fun azureDocumentResource(config: AzureAccountConfig): String? {
    val host = azureInferenceTarget(config)?.host ?: return null
    val resource = when {
        host.endsWith(".services.ai.azure.com") ||
                host.endsWith(".openai.azure.com") ||
                host.endsWith(".cognitiveservices.azure.com") -> host.substringBefore('.')

        else -> return null
    }
    if (resource.isEmpty()) return null
    return resource
}
