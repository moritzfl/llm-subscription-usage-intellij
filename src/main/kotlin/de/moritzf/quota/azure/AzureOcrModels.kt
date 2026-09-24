package de.moritzf.quota.azure

import java.net.URI

internal fun isAzureOcrModel(name: String): Boolean {
    val model = name.lowercase()
    return model.startsWith("mistral-ocr-") || model.startsWith("mistral-document-ai-") ||
            model.startsWith("cohere-parse-")
}

internal const val AZURE_DOCUMENT_INTELLIGENCE_LAYOUT = "doc-intelligence/prebuilt-layout"
private const val COHERE_SELECTION_PREFIX = "cohere:"

internal fun isAzureCohereParseModel(name: String): Boolean = name.startsWith("cohere-parse-", ignoreCase = true)
internal fun isAzureCohereSelection(selection: String): Boolean = selection.startsWith(COHERE_SELECTION_PREFIX)
internal fun azureOcrDeploymentId(selection: String): String = selection.removePrefix(COHERE_SELECTION_PREFIX)

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
    return available.filter { AZURE_DEPLOYMENT_NAME.matches(azureOcrDeploymentId(it)) }.distinct().sorted() +
            listOfNotNull(AZURE_DOCUMENT_INTELLIGENCE_LAYOUT.takeIf { documentIntelligenceAvailable })
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
