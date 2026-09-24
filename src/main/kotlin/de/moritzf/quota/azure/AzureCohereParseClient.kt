package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralOcrWriteResult
import de.moritzf.quota.openai.proxy.pdf.PdfImageIoPlugins
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import javax.imageio.ImageIO
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/** Cohere's Parse API accepts images only; PDFs are rendered locally, one request per page. */
internal class AzureCohereParseClient(
    private val httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
    private val post: (HttpRequest) -> AzureOcrResponse = { request ->
        val result = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        AzureOcrResponse(result.statusCode(), result.body())
    },
) {
    fun convertDocument(
        cli: AzureCli,
        config: AzureAccountConfig,
        deployment: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): String {
        if (!AZURE_DEPLOYMENT_NAME.matches(deployment)) throw AzureOcrException("Invalid Cohere Parse deployment name.")
        val endpoint = azureCohereParseUri(config)
            ?: throw AzureOcrException("Cohere Parse needs an Azure Foundry resource name or endpoint.")
        val source = AzureDocumentInput(httpClient).read(documentUrl, localFile)
        val markdownOutput = outputFile ?: DocumentMarkdown.defaultOutput(localFile)
        val pages = mutableListOf<String>()
        val imagePaths = mutableListOf<String>()
        val responses = mutableListOf<String>()

        fun parseImage(image: BufferedImage, page: Int) {
            val bytes = ByteArrayOutputStream().use { stream ->
                if (!ImageIO.write(
                        image,
                        "png",
                        stream
                    )
                ) throw AzureOcrException("Could not encode document page as PNG.")
                stream.toByteArray()
            }
            if (bytes.size > AzureDocumentInput.MAX_DOCUMENT_BYTES) {
                throw AzureOcrException("Rendered page exceeds Cohere Parse's 20 MB image limit.")
            }
            val body = JsonSupport.json.encodeToString(
                AzureCohereParseRequest(
                    deployment, AzureCohereParseDocument(
                        "image_url", "data:image/png;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
                    )
                ),
            )

            fun send(): AzureOcrResponse {
                val token = cli.accessToken(azureScopeForUrl(endpoint.toString()), config.subscriptionId).accessToken
                return post(
                    HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(180))
                        .header("Authorization", "Bearer $token")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build()
                )
            }

            val first = send()
            val response = if (first.status == 401) send() else first
            if (response.status !in 200..299) {
                val detail = MistralOcrClient.mistralErrorDetail(response.body)
                throw AzureOcrException(
                    "Cohere Parse failed (HTTP ${response.status})${
                        detail?.let { ": $it" }.orEmpty()
                    }.", response.status
                )
            }
            val result = try {
                JsonSupport.json.decodeFromString<AzureCohereParseResponse>(response.body)
            } catch (_: Exception) {
                throw AzureOcrException("Cohere Parse returned invalid page data.")
            }
            if (result.pages.isEmpty()) throw AzureOcrException("Cohere Parse returned no pages.")
            responses += response.body
            result.pages.forEach { item ->
                val parsed = item.markdown ?: throw AzureOcrException("Cohere Parse returned no markdown.")
                var markdown = parsed.content
                for (figure in parsed.images.orEmpty()) {
                    val id = figure.id.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) } ?: continue
                    val stem = markdownOutput?.fileName?.toString()?.substringBeforeLast('.') ?: "document"
                    val name = "$stem-cohere-p$page-$id.png"
                    val crop = if (includeImages && markdownOutput != null) figure.box?.crop(image) else null
                    val target = markdownOutput?.parent?.resolve(name) ?: Path.of(name)
                    val written = crop != null && runCatching {
                        target.parent?.let(Files::createDirectories)
                        ImageIO.write(crop, "png", target.toFile())
                    }.getOrDefault(false)
                    if (written) imagePaths += target.toString()
                    val link = Regex("!\\[([^]]*)]\\(${Regex.escape(id)}\\)")
                    markdown = link.replace(markdown) { match ->
                        if (written) "![${match.groupValues[1]}]($name)" else match.groupValues[1]
                    }
                }
                pages += markdown
            }
        }

        if (source.mime == "application/pdf") {
            PdfImageIoPlugins.ensureRegistered()
            Loader.loadPDF(source.bytes).use { pdf ->
                val from = pageFrom ?: 1
                val to = pageTo ?: pdf.numberOfPages
                if (from < 1 || to < from || to > pdf.numberOfPages || to - from >= MAX_PAGES) {
                    throw AzureOcrException("Cohere Parse accepts 1 to $MAX_PAGES PDF pages per request; set pageFrom/pageTo for larger files.")
                }
                val renderer = PDFRenderer(pdf)
                for (page in from..to) {
                    val box = pdf.getPage(page - 1).cropBox
                    if (box.width.toDouble() * box.height.toDouble() * (DPI / 72.0) * (DPI / 72.0) > MAX_PIXELS) {
                        throw AzureOcrException("PDF page $page is too large to render for Cohere Parse.")
                    }
                    parseImage(renderer.renderImageWithDPI(page - 1, DPI, ImageType.RGB), page)
                }
            }
        } else {
            if ((pageFrom ?: 1) != 1 || (pageTo ?: 1) != 1) throw AzureOcrException("Image documents have one page.")
            val image = ImageIO.read(source.bytes.inputStream())
                ?: throw AzureOcrException("Could not decode image for Cohere Parse.")
            if (image.width.toLong() * image.height > MAX_PIXELS) throw AzureOcrException("Image is too large for Cohere Parse.")
            parseImage(image, 1)
        }
        if (markdownOutput == null && responses.size == 1) return McpJson.providerJsonOrRaw(responses.single())
        val markdown = pages.joinToString("\n\n")
        if (markdownOutput == null) return DocumentMarkdown.resultJson(markdown, null, pageCount = pages.size)
        markdownOutput.parent?.let(Files::createDirectories)
        Files.writeString(markdownOutput, markdown)
        return JsonSupport.json.encodeToString(MistralOcrWriteResult(markdownOutput.toString(), imagePaths, pages.size))
    }

    companion object {
        private const val MAX_PAGES = 20
        private const val MAX_PIXELS = 50_000_000
        private const val DPI = 144f
    }
}

private fun AzureCohereParseBox.crop(image: BufferedImage): BufferedImage? {
    val left = x0.coerceIn(0, image.width - 1)
    val top = y0.coerceIn(0, image.height - 1)
    val right = x1.coerceIn(left + 1, image.width)
    val bottom = y1.coerceIn(top + 1, image.height)
    return if (right - left > 1 && bottom - top > 1) image.getSubimage(left, top, right - left, bottom - top) else null
}

@Serializable
internal data class AzureCohereParseRequest(
    val model: String,
    val document: AzureCohereParseDocument,
    @SerialName("output_format") val outputFormat: String = "markdown",
)

@Serializable
internal data class AzureCohereParseDocument(val type: String, @SerialName("image_url") val imageUrl: String)

@Serializable
internal data class AzureCohereParseResponse(val pages: List<AzureCohereParsePage> = emptyList())

@Serializable
internal data class AzureCohereParsePage(val markdown: AzureCohereParseMarkdown? = null)

@Serializable
internal data class AzureCohereParseMarkdown(val content: String, val images: List<AzureCohereParseImage>? = null)

@Serializable
internal data class AzureCohereParseImage(
    val id: String,
    @SerialName("bounding_box") val box: AzureCohereParseBox? = null
)

@Serializable
internal data class AzureCohereParseBox(
    @SerialName("top_left_x") val x0: Int,
    @SerialName("top_left_y") val y0: Int,
    @SerialName("bottom_right_x") val x1: Int,
    @SerialName("bottom_right_y") val y1: Int,
)
