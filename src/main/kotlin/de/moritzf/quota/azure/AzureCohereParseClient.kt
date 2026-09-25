package de.moritzf.quota.azure

import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralOcrWriteResult
import de.moritzf.quota.openai.proxy.pdf.PdfImageIoPlugins
import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentImageWriter
import de.moritzf.quota.shared.OriginalPdf
import de.moritzf.quota.shared.ProviderDocumentImage
import de.moritzf.quota.shared.rewriteMarkdownImageLinks
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRegion
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
        imageOptions: DocumentImageOptions = DocumentImageOptions(),
        progress: DocumentConversionProgress = DocumentConversionProgress.NONE,
    ): String {
        if (!AZURE_DEPLOYMENT_NAME.matches(deployment)) throw AzureOcrException("Invalid Cohere Parse deployment name.")
        val endpoint = azureCohereParseUri(config)
            ?: throw AzureOcrException("Cohere Parse needs an Azure Foundry resource name or endpoint.")
        val source = AzureDocumentInput(httpClient).read(documentUrl, localFile)
        val markdownOutput = outputFile ?: DocumentMarkdown.defaultOutput(localFile)
        val pages = mutableListOf<String>()
        val responses = mutableListOf<String>()
        val imageWriter =
            markdownOutput?.let { DocumentImageWriter(it, imageOptions, OriginalPdf.bytes(source.bytes, source.mime)) }
        imageWriter.use {

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
                    val token =
                        cli.accessToken(azureScopeForUrl(endpoint.toString()), config.subscriptionId).accessToken
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
                        "Cohere Parse failed for page $page (HTTP ${response.status})${
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
                    parsed.images.orEmpty().forEachIndexed { index, figure ->
                        val id =
                            figure.id.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,100}")) } ?: return@forEachIndexed
                        val box = figure.box
                        val region = box?.let {
                            PdfFigureRegion.fromPixels(
                                page,
                                listOf(it.x0.toDouble(), it.y0.toDouble(), it.x1.toDouble(), it.y1.toDouble()),
                                image.width.toDouble(), image.height.toDouble()
                            )
                        }
                        val link = if (includeImages) imageWriter?.write(page, index + 1, region) {
                            box?.crop(image)?.let { crop ->
                                ByteArrayOutputStream().use { stream ->
                                    if (!ImageIO.write(
                                            crop,
                                            "png",
                                            stream
                                        )
                                    ) throw AzureOcrException("Could not encode figure as PNG.")
                                    ProviderDocumentImage(stream.toByteArray(), "png")
                                }
                            }
                        } else null
                        markdown = rewriteMarkdownImageLinks(markdown, mapOf(id to link))
                    }
                    pages += markdown
                }
            }

            if (source.mime == "application/pdf") {
                PdfImageIoPlugins.ensureRegistered()
                Loader.loadPDF(source.bytes).use { pdf ->
                    val from = pageFrom ?: 1
                    val to = pageTo ?: pdf.numberOfPages
                    if (from < 1 || to < from || to > pdf.numberOfPages) {
                        throw AzureOcrException("pageFrom/pageTo out of range (document has ${pdf.numberOfPages} pages).")
                    }
                    val work = to - from + 1
                    val renderer = PDFRenderer(pdf)
                    var done = 0
                    for (page in from..to) {
                        progress.update(done, work, "Page $page of ${pdf.numberOfPages}")
                        val box = pdf.getPage(page - 1).cropBox
                        if (box.width.toDouble() * box.height.toDouble() * (DPI / 72.0) * (DPI / 72.0) > MAX_PIXELS) {
                            throw AzureOcrException("PDF page $page is too large to render for Cohere Parse.")
                        }
                        parseImage(renderer.renderImageWithDPI(page - 1, DPI, ImageType.RGB), page)
                        done++
                        progress.update(done, work, "Converted $done of $work pages")
                    }
                }
            } else {
                if ((pageFrom ?: 1) != 1 || (pageTo
                        ?: 1) != 1
                ) throw AzureOcrException("Image documents have one page.")
                val image = ImageIO.read(source.bytes.inputStream())
                    ?: throw AzureOcrException("Could not decode image for Cohere Parse.")
                if (image.width.toLong() * image.height > MAX_PIXELS) throw AzureOcrException("Image is too large for Cohere Parse.")
                progress.update(0, 1, "Page 1 of 1")
                parseImage(image, 1)
                progress.update(1, 1, "Converted 1 of 1 pages")
            }
            if (markdownOutput == null && responses.size == 1) return McpJson.providerJsonOrRaw(responses.single())
            val markdown = pages.joinToString("\n\n")
            if (markdownOutput == null) return DocumentMarkdown.resultJson(markdown, null, pageCount = pages.size)
            DocumentMarkdown.writeAtomically(markdownOutput, markdown)
            imageWriter?.commit()
            return JsonSupport.json.encodeToString(
                MistralOcrWriteResult(
                    markdownOutput.toString(),
                    imageWriter?.imageFiles.orEmpty(), pages.size, imageWriter?.warnings.orEmpty()
                )
            )
        }
    }

    companion object {
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
