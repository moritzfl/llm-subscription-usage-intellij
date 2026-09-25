package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralMarkdownWriter
import de.moritzf.quota.mistral.MistralOcrDocumentDto
import de.moritzf.quota.mistral.MistralOcrRequestDto
import de.moritzf.quota.mistral.MistralOcrResponseDto
import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.PageExtractor

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
        progress: DocumentConversionProgress = DocumentConversionProgress.NONE,
    ): String {
        if (!AZURE_DEPLOYMENT_NAME.matches(deployment)) throw AzureOcrException("Invalid Azure OCR deployment name.")
        val endpoint = azureOcrUri(config)
            ?: throw AzureOcrException("Azure OCR needs an Azure Foundry resource name or endpoint.")
        progress.update(0, 0, "Reading document")
        val source = input.read(documentUrl, localFile)
        val markdownOutput = outputFile ?: MistralOcrClient.defaultMarkdownOutput(localFile)
        val responses = mutableListOf<AzureOcrChunkResult>()
        var singleResponse: String? = null
        val writer = markdownOutput?.let { MistralMarkdownWriter(it, includeImages) }
        writer.use {
            fun convertPart(part: AzureDocumentSource, from: Int, to: Int, total: Int) {
                val detail = "Pages $from–$to of $total"
                progress.update(from - 1, total, detail)
                val document = if (part.mime == "application/pdf") {
                    MistralOcrDocumentDto("document_url", documentUrl = part.dataUrl)
                } else MistralOcrDocumentDto("image_url", imageUrl = part.dataUrl)
                val body = JsonSupport.json.encodeToString(
                    MistralOcrRequestDto(deployment, document,
                        includeImageBase64 = includeImages && writer != null, includeBlocks = false),
                )
                fun sendWithCliToken(): AzureOcrResponse {
                    val token = cli.accessToken(azureScopeForUrl(endpoint.toString()), config.subscriptionId).accessToken
                    progress.update(from - 1, total, detail)
                    return post(HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(180))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build())
                }
                val first = sendWithCliToken()
                val response = if (first.status == 401) sendWithCliToken() else first
                progress.update(from - 1, total, detail)
                if (response.status !in 200..299) {
                    val error = MistralOcrClient.mistralErrorDetail(response.body)
                    throw AzureOcrException(
                        "Azure OCR failed for pages $from–$to (HTTP ${response.status})${error?.let { ": $it" }.orEmpty()}.",
                        response.status,
                    )
                }
                val parsed = try {
                    JsonSupport.json.decodeFromString<MistralOcrResponseDto>(response.body)
                } catch (_: Exception) {
                    throw AzureOcrException("Azure OCR returned invalid page data for pages $from–$to.")
                }
                if (parsed.pages.size != to - from + 1) {
                    throw AzureOcrException("Azure OCR returned ${parsed.pages.size} pages for pages $from–$to; output was not saved.")
                }
                if (writer != null) writer.append(parsed.pages) else {
                    singleResponse = response.body
                    responses += AzureOcrChunkResult(from, to, JsonSupport.json.parseToJsonElement(response.body))
                }
                progress.update(to, total, "Converted $to of $total pages")
            }

            if (source.mime == "application/pdf") {
                Loader.loadPDF(source.bytes).use { pdf ->
                    val total = pdf.numberOfPages
                    if (total < 1) throw AzureOcrException("PDF has no pages.")
                    var from = 1
                    while (from <= total) {
                        var to = minOf(from + MAX_PAGES_PER_REQUEST - 1, total)
                        progress.update(from - 1, total, "Preparing pages $from–$to of $total")
                        var part = source
                        if (from != 1 || to != total) {
                            while (true) {
                                val bytes = PageExtractor(pdf, from, to).extract().use { chunk ->
                                    ByteArrayOutputStream().use { stream -> chunk.save(stream); stream.toByteArray() }
                                }
                                if (bytes.size <= AzureDocumentInput.MAX_DOCUMENT_BYTES) {
                                    part = AzureDocumentSource(bytes, "application/pdf")
                                    break
                                }
                                if (to == from) throw AzureOcrException("PDF page $from exceeds Azure OCR's 20 MB request limit.")
                                to = from + (to - from) / 2
                                progress.update(from - 1, total, "Preparing pages $from–$to of $total")
                            }
                        }
                        convertPart(part, from, to, total)
                        from = to + 1
                    }
                }
            } else convertPart(source, 1, 1, 1)
            if (writer != null) return JsonSupport.json.encodeToString(writer.commit())
        }
        if (responses.size == 1) return McpJson.providerJsonOrRaw(checkNotNull(singleResponse))
        // Keep each upstream response intact when no output file was requested.
        return JsonSupport.json.encodeToString(AzureOcrChunkResults(responses.last().pageTo, responses))
    }

    companion object {
        private const val MAX_PAGES_PER_REQUEST = 30
    }
}

internal data class AzureOcrResponse(val status: Int, val body: String)

@Serializable
private data class AzureOcrChunkResult(
    @SerialName("page_from") val pageFrom: Int,
    @SerialName("page_to") val pageTo: Int,
    val response: JsonElement,
)

@Serializable
private data class AzureOcrChunkResults(
    @SerialName("page_count") val pageCount: Int,
    val chunks: List<AzureOcrChunkResult>,
)
