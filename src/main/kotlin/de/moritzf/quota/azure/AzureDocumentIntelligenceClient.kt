package de.moritzf.quota.azure

import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentImageWriter
import de.moritzf.quota.shared.OriginalPdf
import de.moritzf.quota.shared.ProviderDocumentImage
import de.moritzf.quota.shared.rewriteMarkdownImageLinks
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRegion
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Document Intelligence's prebuilt layout is a service model, not a Foundry deployment. */
internal class AzureDocumentIntelligenceClient(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build(),
    private val send: (HttpRequest) -> AzureDocumentIntelligenceResponse = { request ->
        val result = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        AzureDocumentIntelligenceResponse(
            result.statusCode(),
            result.headers().map().mapValues { it.value.firstOrNull().orEmpty() }, result.body()
        )
    },
) {
    fun convertDocument(
        cli: AzureCli,
        config: AzureAccountConfig,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        imageOptions: DocumentImageOptions = DocumentImageOptions(),
    ): String {
        val endpoint = azureDocumentIntelligenceUri(config)
            ?: throw AzureOcrException("Document Intelligence needs an Azure Cognitive Services resource name or endpoint.")
        val source = AzureDocumentInput(httpClient).read(documentUrl, localFile)
        val markdownOutput = outputFile ?: DocumentMarkdown.defaultOutput(localFile)
        val writeImages = includeImages && markdownOutput != null
        val uri = URI.create(endpoint.toString() + if (writeImages) "&output=figures" else "")
        val body = JsonSupport.json.encodeToString(
            AzureDocumentAnalyzeRequest(
                base64Source = Base64.getEncoder().encodeToString(source.bytes),
            )
        )

        fun request(uri: URI, method: String, body: String? = null): AzureDocumentIntelligenceResponse {
            fun sendWithToken(): AzureDocumentIntelligenceResponse {
                val token = cli.accessToken(AZURE_COGNITIVE_SCOPE, config.subscriptionId).accessToken
                val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", if (method == "GET") "*/*" else "application/json")
                if (body == null) builder.GET() else builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                return send(builder.build())
            }

            val first = sendWithToken()
            return if (first.status == 401) sendWithToken() else first
        }

        val started = request(uri, "POST", body)
        if (started.status != 202) throw azureDocumentError("Document Intelligence analysis", started)
        val location = started.header("Operation-Location")
            ?.let { runCatching { URI(it) }.getOrNull() }
            ?: throw AzureOcrException("Document Intelligence returned no analysis location.")
        val prefix = "/documentintelligence/documentModels/prebuilt-layout/analyzeResults/"
        if (location.scheme != "https" || location.host != endpoint.host || location.port != -1 ||
            !location.path.startsWith(prefix) || location.rawQuery != "api-version=2024-11-30" ||
            !location.path.removePrefix(prefix).matches(Regex("[0-9a-fA-F-]{36}"))
        ) throw AzureOcrException("Document Intelligence returned an invalid analysis location.")

        val deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos()
        var delay = started.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 10) ?: 2L
        var resultBody: String? = null
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(delay * 1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AzureOcrException("Document Intelligence analysis was cancelled.")
            }
            val poll = request(location, "GET")
            if (poll.status != 200) throw azureDocumentError("Document Intelligence result", poll)
            val json =
                runCatching { JsonSupport.json.parseToJsonElement(poll.body.decodeToString()) as? JsonObject }.getOrNull()
                    ?: throw AzureOcrException("Document Intelligence returned an invalid analysis result.")
            when ((json["status"] as? JsonPrimitive)?.contentOrNull?.lowercase()) {
                "succeeded" -> {
                    resultBody = poll.body.decodeToString(); break
                }

                "failed", "canceled" -> throw AzureOcrException("Document Intelligence analysis failed.")
                "running", "notstarted" -> delay = poll.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 10) ?: 2L
                else -> throw AzureOcrException("Document Intelligence returned an unknown analysis status.")
            }
        }
        val jsonText = resultBody ?: throw AzureOcrException("Document Intelligence analysis timed out.")
        val root = JsonSupport.json.parseToJsonElement(jsonText) as JsonObject
        val result = root["analyzeResult"] as? JsonObject
            ?: throw AzureOcrException("Document Intelligence returned no document content.")
        val markdown = (result["content"] as? JsonPrimitive)?.contentOrNull
            ?: throw AzureOcrException("Document Intelligence returned no markdown.")
        if (markdownOutput == null) return McpJson.providerJsonOrRaw(jsonText)

        val figures = (result["figures"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        DocumentImageWriter(markdownOutput, imageOptions, OriginalPdf.bytes(source.bytes, source.mime)).use { images ->
            val links = mutableMapOf<String, String?>()
            if (writeImages) {
                figures.forEachIndexed { index, figure ->
                    val id = (figure["id"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) } ?: return@forEachIndexed
                    val region = azureFigureRegion(figure, result["pages"] as? JsonArray)
                    links["figures/$id"] = images.write(region?.page ?: 1, index + 1, region) {
                        val figureUri = URI.create(location.toString().substringBefore('?') + "/figures/$id?api-version=2024-11-30")
                        val image = request(figureUri, "GET")
                        if (image.status != 200 || image.body.size > AzureDocumentInput.MAX_DOCUMENT_BYTES || image.body.size < 8) {
                            throw AzureOcrException("Could not retrieve Document Intelligence figure $id.")
                        }
                        ProviderDocumentImage(image.body, "png")
                    }
                }
            }
            var content = rewriteMarkdownImageLinks(markdown, links)
            content = Regex("!\\[([^]]*)]\\(figures/[^)]+\\)").replace(content) { it.groupValues[1] }
            val json = DocumentMarkdown.resultJson(content, markdownOutput, images.imageFiles,
                pageCount = (result["pages"] as? JsonArray)?.size, warnings = images.warnings)
            images.commit()
            return json
        }
    }

    private fun azureDocumentError(context: String, response: AzureDocumentIntelligenceResponse): AzureOcrException {
        val root =
            runCatching { JsonSupport.json.parseToJsonElement(response.body.decodeToString()) as? JsonObject }.getOrNull()
        val detail = ((root?.get("error") as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        return AzureOcrException(
            "$context failed (HTTP ${response.status})${detail?.let { ": $it" }.orEmpty()}.",
            response.status
        )
    }
}

internal fun azureFigureRegion(figure: JsonObject, pages: JsonArray?): PdfFigureRegion? {
    val region = (figure["boundingRegions"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return null
    val pageNumber = (region["pageNumber"] as? JsonPrimitive)?.intOrNull ?: return null
    val page = pages.orEmpty().filterIsInstance<JsonObject>()
        .firstOrNull { (it["pageNumber"] as? JsonPrimitive)?.intOrNull == pageNumber } ?: return null
    val polygon = (region["polygon"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        ?.takeIf { it.size == 8 } ?: return null
    val xs = polygon.filterIndexed { index, _ -> index % 2 == 0 }
    val ys = polygon.filterIndexed { index, _ -> index % 2 == 1 }
    return PdfFigureRegion.fromPixels(pageNumber, listOf(xs.min(), ys.min(), xs.max(), ys.max()),
        (page["width"] as? JsonPrimitive)?.doubleOrNull, (page["height"] as? JsonPrimitive)?.doubleOrNull)
}

@Serializable
internal data class AzureDocumentAnalyzeRequest(val base64Source: String)

internal class AzureDocumentIntelligenceResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}
