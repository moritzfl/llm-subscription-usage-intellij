package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralOcrDocumentDto
import de.moritzf.quota.mistral.MistralOcrRequestDto
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

internal class AzureOcrException(message: String, val statusCode: Int? = null) : Exception(message)

/** The Azure Mistral OCR route accepts inline documents, not Mistral's /files uploads. */
internal class AzureOcrClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
    private val post: (HttpRequest) -> AzureOcrResponse = { request ->
        val result = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        AzureOcrResponse(result.statusCode(), result.body())
    },
) {
    private val input = AzureDocumentInput(httpClient)

    fun convertDocument(
        cli: AzureCli,
        config: AzureAccountConfig,
        deployment: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
    ): String {
        if (!AZURE_DEPLOYMENT_NAME.matches(deployment)) throw AzureOcrException("Invalid Azure OCR deployment name.")
        val endpoint = azureOcrUri(config)
            ?: throw AzureOcrException("Azure OCR needs an Azure Foundry resource name or endpoint.")
        val source = resolveDocument(documentUrl, localFile)
        val markdownOutput = outputFile ?: MistralOcrClient.defaultMarkdownOutput(localFile)
        val body = JsonSupport.json.encodeToString(
            MistralOcrRequestDto(
                model = deployment,
                document = source,
                includeImageBase64 = includeImages && markdownOutput != null,
                includeBlocks = false,
            ),
        )

        fun sendWithCliToken(): AzureOcrResponse {
            val token = cli.accessToken(azureScopeForUrl(endpoint.toString()), config.subscriptionId).accessToken
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            return post(request)
        }

        val first = sendWithCliToken()
        val response = if (first.status == 401) sendWithCliToken() else first
        if (response.status !in 200..299) {
            val detail = MistralOcrClient.mistralErrorDetail(response.body)
            throw AzureOcrException(
                "Azure OCR failed (HTTP ${response.status})${detail?.let { ": $it" }.orEmpty()}.",
                response.status,
            )
        }
        if (markdownOutput == null) return McpJson.providerJsonOrRaw(response.body)
        val written = MistralOcrClient.writeMarkdown(response.body, markdownOutput, includeImages)
        return JsonSupport.json.encodeToString(written)
    }

    private fun resolveDocument(documentUrl: String?, localFile: Path?): MistralOcrDocumentDto {
        val source = input.read(documentUrl, localFile)
        return if (source.mime == "application/pdf") MistralOcrDocumentDto("document_url", documentUrl = source.dataUrl)
        else MistralOcrDocumentDto("image_url", imageUrl = source.dataUrl)
    }
}

internal data class AzureOcrResponse(val status: Int, val body: String)
