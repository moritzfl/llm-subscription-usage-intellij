package de.moritzf.quota.idea.action

import de.moritzf.quota.azure.AZURE_DOCUMENT_INTELLIGENCE_LAYOUT
import de.moritzf.quota.azure.AzureCli
import de.moritzf.quota.azure.AzureCohereParseClient
import de.moritzf.quota.azure.AzureDocumentIntelligenceClient
import de.moritzf.quota.azure.AzureOcrClient
import de.moritzf.quota.azure.azureOcrDeploymentId
import de.moritzf.quota.azure.isAzureCohereSelection
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.AzureQuotaProvider
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.mcp.CodexMcpClient
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.openai.proxy.pdf.PdfPages
import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.supergrok.SuperGrokDocumentClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.zai.ZaiOcrClient
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** IDE-side entry point; no dependency on the optional IntelliJ MCP server. */
internal object PdfDocumentConversion {
    fun availableProviders(): List<DocumentToMarkdownProvider> {
        val settings = QuotaSettingsState.getInstance()
        return DocumentToMarkdownProvider.entries.filter { choice ->
            val descriptor = ProviderCatalog.get(choice.providerType)
            settings.accountsOf(choice.providerType).any { descriptor.isDocumentConfiguredForAccount(it.id) }
        }
    }

    fun convert(
        provider: DocumentToMarkdownProvider, source: Path, output: Path, includeImages: Boolean,
        imageOptions: DocumentImageOptions = DocumentImageOptions(),
        progress: DocumentConversionProgress = DocumentConversionProgress.NONE,
    ): List<String> {
        progress.update(0, 0, "Preparing document conversion")
        require(PdfPages.isPdf(source)) { "Select a readable PDF file." }
        val type = provider.providerType
        val account = AccountResolver.resolve(type, capability = AccountCapability.DOCUMENT_TO_MARKDOWN)
        val response = when (provider) {
            DocumentToMarkdownProvider.MISTRAL -> {
                val key = MistralApiKeyStore.forAccount(account.id).loadBlocking()
                    ?: error("Mistral API key missing.")
                MistralOcrClient.createDefault().convertDocument(key, localFile = source, outputFile = output,
                    includeImages = includeImages, imageOptions = imageOptions)
            }
            DocumentToMarkdownProvider.AZURE -> {
                val selection = AzureQuotaProvider.ocrDeploymentForAccount(account.id)
                    ?: error("Select an Azure document model in settings.")
                val executable = AzureQuotaProvider.executableForAccount(account.id)
                    ?: error("Azure CLI not found.")
                val cli = AzureCli(executable)
                val config = AzureQuotaProvider.configForAccount(account.id)
                when {
                    selection == AZURE_DOCUMENT_INTELLIGENCE_LAYOUT ->
                        AzureDocumentIntelligenceClient().convertDocument(cli, config,
                            localFile = source, outputFile = output, includeImages = includeImages, imageOptions = imageOptions)
                    isAzureCohereSelection(selection) ->
                        AzureCohereParseClient().convertDocument(cli, config, azureOcrDeploymentId(selection),
                            localFile = source, outputFile = output, includeImages = includeImages,
                            imageOptions = imageOptions, progress = progress)
                    else -> AzureOcrClient().convertDocument(cli, config, selection,
                        localFile = source, outputFile = output, includeImages = includeImages, progress = progress,
                        imageOptions = imageOptions)
                }
            }
            DocumentToMarkdownProvider.ZAI -> {
                val key = ZaiApiKeyStore.forAccount(account.id).loadBlocking()
                    ?: error("Z.ai API key missing.")
                ZaiOcrClient.createDefault().convertDocument(key, localFile = source, outputFile = output,
                    includeImages = includeImages, imageOptions = imageOptions, progress = progress)
            }
            DocumentToMarkdownProvider.OPEN_AI -> {
                val auth = QuotaAuthService.getInstance()
                val client = CodexMcpClient(
                    accessTokenProvider = { auth.getAccessTokenBlocking(account.id, type) },
                    accountIdProvider = { auth.getAccountId(account.id, type) },
                    tokenRefresher = { auth.forceRefreshBlocking(account.id, type, it) },
                )
                client.documentToMarkdown(localFile = source, outputFile = output,
                    includeImages = includeImages).body
            }
            DocumentToMarkdownProvider.SUPERGROK -> {
                val auth = QuotaAuthService.getInstance()
                val token = auth.getAccessTokenBlocking(account.id, type) ?: error("Grok login required.")
                val client = SuperGrokDocumentClient()
                try {
                    client.convertDocument(token, localFile = source, outputFile = output,
                        includeImages = includeImages)
                } catch (exception: SuperGrokQuotaException) {
                    if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
                    val refreshed = auth.forceRefreshBlocking(account.id, type, token) ?: throw exception
                    client.convertDocument(refreshed, localFile = source, outputFile = output,
                        includeImages = includeImages)
                }
            }
        }
        return checkConversionResult(response, output)
    }
}

internal fun checkConversionResult(response: String, output: Path): List<String> {
    val json = runCatching { JsonSupport.json.parseToJsonElement(response) as? JsonObject }.getOrNull()
    val error = (json?.get("error") as? JsonPrimitive)?.contentOrNull
    if (!error.isNullOrBlank()) throw IllegalStateException(error)
    val returnedFile = (json?.get("output_file") as? JsonPrimitive)?.contentOrNull
        ?: throw IllegalStateException("Provider returned no Markdown output path.")
    if (Path.of(returnedFile).toAbsolutePath().normalize() != output.toAbsolutePath().normalize()) {
        throw IllegalStateException("Provider wrote Markdown to an unexpected output path.")
    }
    if (!Files.isRegularFile(output)) throw IllegalStateException("Provider returned no Markdown output file.")
    return (json["warnings"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
