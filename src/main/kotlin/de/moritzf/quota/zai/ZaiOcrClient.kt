package de.moritzf.quota.zai

import de.moritzf.quota.openai.proxy.pdf.PdfPages
import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentLimits
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.DocumentImageWriter
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.OriginalPdf
import de.moritzf.quota.shared.ProviderDocumentImage
import de.moritzf.quota.shared.rewriteMarkdownImageLinks
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRegion
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRenderer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import org.apache.pdfbox.Loader
import org.apache.pdfbox.multipdf.PageExtractor
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

open class ZaiOcrClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val layoutParsingUri: URI = LAYOUT_PARSING_URI,
) {
    private var post: ((HttpRequest) -> ZaiHttpResult)? = null

    internal constructor(post: (HttpRequest) -> ZaiHttpResult) : this() {
        this.post = post
    }

    open fun convertDocument(
        apiKey: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_MODEL,
        imageOptions: DocumentImageOptions = DocumentImageOptions(),
    ): String = convertDocument(
        apiKey, documentUrl, localFile, outputFile, includeImages, model, imageOptions, DocumentConversionProgress.NONE,
    )

    internal fun convertDocument(
        apiKey: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_MODEL,
        imageOptions: DocumentImageOptions = DocumentImageOptions(),
        progress: DocumentConversionProgress,
    ): String {
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val selectedModel = model.trim().ifBlank { DEFAULT_MODEL }
        if (documentUrl.isNullOrBlank() && localFile != null && PdfPages.isPdf(localFile)) {
            DocumentLimits.inlineOverflowMessage(localFile)?.let { throw ZaiQuotaException(it) }
            val pages = PdfPages.pageCount(localFile)
            val size = Files.size(localFile)
            if (pages == null && size > MAX_PDF_BYTES) {
                throw ZaiQuotaException("Could not read PDF page count.")
            }
            if (pages != null && (pages > MAX_PAGES || size > MAX_PDF_BYTES)) {
                return convertPdfChunks(
                    token, localFile, outputFile, includeImages, selectedModel, imageOptions, progress, pages,
                )
            }
        }
        val markdownOutput = outputFile ?: defaultMarkdownOutput(localFile)
        val knownPages = localFile?.let { PdfPages.pageCount(it) } ?: 0
        progress.update(0, knownPages, "Reading document")
        val response = request(
            token, selectedModel, resolveFile(documentUrl, localFile), includeImages && markdownOutput != null,
        )
        if (knownPages > 0) progress.update(knownPages, knownPages, "Converted $knownPages of $knownPages pages")
        if (markdownOutput == null) {
            return McpJson.providerJsonOrRaw(response.body)
        }
        val written = writeMarkdown(
            response.body, markdownOutput, includeImages, imageOptions,
            OriginalPdf.source(localFile, documentUrl),
        ) { url -> downloadBytes(url) }
        return JsonSupport.json.encodeToString(written)
    }

    private fun convertPdfChunks(
        token: String,
        localFile: Path,
        outputFile: Path?,
        includeImages: Boolean,
        model: String,
        imageOptions: DocumentImageOptions,
        progress: DocumentConversionProgress,
        total: Int,
    ): String {
        if (total < 1) throw ZaiQuotaException("PDF has no pages.")
        val markdownOutput = outputFile ?: defaultMarkdownOutput(localFile)
        val writeImages = includeImages && markdownOutput != null
        val destination = markdownOutput ?: throw ZaiQuotaException("Provide a local PDF so converted markdown can be saved.")
        val parts = mutableListOf<String>()
        val writer = DocumentImageWriter(destination, imageOptions, OriginalPdf.local(localFile))
        writer.use { images ->
            Loader.loadPDF(localFile.toFile()).use { pdf ->
                var from = 1
                while (from <= total) {
                    var to = minOf(from + MAX_PAGES - 1, total)
                    progress.update(from - 1, total, "Preparing pages $from–$to of $total")
                    var bytes: ByteArray
                    while (true) {
                        bytes = PageExtractor(pdf, from, to).extract().use { chunk ->
                            ByteArrayOutputStream().use { stream -> chunk.save(stream); stream.toByteArray() }
                        }
                        if (bytes.size <= MAX_PDF_BYTES) break
                        if (to == from) throw ZaiQuotaException("PDF page $from exceeds Z.ai OCR's 50 MB request limit.")
                        to = from + (to - from) / 2
                        progress.update(from - 1, total, "Preparing pages $from–$to of $total")
                    }
                    val detail = "Pages $from–$to of $total"
                    progress.update(from - 1, total, detail)
                    val response = request(
                        token, model,
                        "data:application/pdf;base64,${Base64.getEncoder().encodeToString(bytes)}",
                        writeImages, from, to,
                    )
                    val parsed = decodeResponse(response.body, from, to)
                    val reported = reportedPages(parsed)
                    val expected = to - from + 1
                    if (reported != expected) {
                        throw ZaiQuotaException(
                            "Z.ai OCR returned $reported pages for pages $from–$to; output was not saved.",
                            200, response.body,
                        )
                    }
                    val markdown = applyResponse(parsed, writeImages, images, { downloadBytes(it) }, from - 1).trim()
                    if (markdown.isEmpty()) {
                        throw ZaiQuotaException("Z.ai OCR returned no markdown for pages $from–$to.", 200, response.body)
                    }
                    parts += markdown
                    progress.update(to, total, "Converted $to of $total pages")
                    from = to + 1
                }
            }
            DocumentMarkdown.writeAtomically(destination, parts.joinToString("\n\n"))
            images.commit()
            return JsonSupport.json.encodeToString(
                ZaiOcrWriteResult(destination.toString(), images.imageFiles, total, images.warnings),
            )
        }
    }

    private fun downloadBytes(url: String): ByteArray? {
        return try {
            val response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            response.takeIf { it.statusCode() in 200..299 }?.body()
        } catch (_: Exception) {
            null
        }
    }

    private fun request(
        token: String,
        model: String,
        file: String,
        returnCropImages: Boolean,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): ZaiHttpResult {
        val body = JsonSupport.json.encodeToString(
            ZaiLayoutParsingRequestDto(model, file, returnCropImages),
        )
        val response = send(postJson(token, body))
        if (response.status == 401 || response.status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", response.status, response.body)
        }
        if (response.status !in 200..299) {
            val range = if (pageFrom != null && pageTo != null) " for pages $pageFrom–$pageTo" else ""
            throw ZaiQuotaException("Z.ai OCR failed$range (HTTP ${response.status}). Try again later.", response.status, response.body)
        }
        return response
    }

    private fun send(request: HttpRequest): ZaiHttpResult {
        post?.let { return it(request) }
        return try {
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            ZaiHttpResult(response.statusCode(), response.body())
        } catch (exception: IOException) {
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun postJson(apiKey: String, body: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(layoutParsingUri)
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    companion object {
        const val DEFAULT_MODEL = "glm-ocr"
        private const val MAX_PAGES = 30
        private const val MAX_PDF_BYTES = 50L * 1024 * 1024
        private val LAYOUT_PARSING_URI = URI.create("https://api.z.ai/api/paas/v4/layout_parsing")

        fun createDefault(): ZaiOcrClient = ZaiOcrClient()

        internal fun resolveFile(documentUrl: String?, localFile: Path?): String {
            val url = documentUrl?.trim().orEmpty()
            if (url.isNotEmpty()) return url
            val path = localFile ?: throw ZaiQuotaException("Provide documentUrl or a local file path.")
            if (!Files.isRegularFile(path)) {
                throw ZaiQuotaException("Local document was not found.")
            }
            DocumentLimits.inlineOverflowMessage(path)?.let { throw ZaiQuotaException(it) }
            val bytes = Files.readAllBytes(path)
            return "data:${mimeType(path, bytes)};base64,${Base64.getEncoder().encodeToString(bytes)}"
        }

        internal fun defaultMarkdownOutput(localFile: Path?): Path? {
            if (localFile == null) return null
            val name = localFile.fileName.toString()
            val stem = name.substringBeforeLast('.', name).ifBlank { name }
            return localFile.resolveSibling("$stem.md")
        }

        internal fun writeMarkdown(
            responseBody: String,
            outputFile: Path,
            includeImages: Boolean = false,
            imageOptions: DocumentImageOptions = DocumentImageOptions(),
            originalPdf: () -> PdfFigureRenderer? = { null },
            pageOffset: Int = 0,
            download: (String) -> ByteArray? = { null },
        ): ZaiOcrWriteResult {
            val parsed = decodeResponse(responseBody)
            DocumentImageWriter(outputFile, imageOptions, originalPdf).use { images ->
                val markdown = applyResponse(parsed, includeImages, images, download, pageOffset)
                DocumentMarkdown.writeAtomically(outputFile, markdown)
                images.commit()
                return ZaiOcrWriteResult(outputFile.toString(), images.imageFiles, parsed.dataInfo?.numPages ?: 0, images.warnings)
            }
        }

        internal fun decodeResponse(responseBody: String, pageFrom: Int? = null, pageTo: Int? = null): ZaiLayoutParsingResponseDto {
            return try {
                JsonSupport.json.decodeFromString<ZaiLayoutParsingResponseDto>(responseBody)
            } catch (exception: Exception) {
                val range = if (pageFrom != null && pageTo != null) " for pages $pageFrom–$pageTo" else ""
                throw ZaiQuotaException("Z.ai OCR returned invalid page data$range.", 200, responseBody, exception)
            }
        }

        internal fun reportedPages(parsed: ZaiLayoutParsingResponseDto): Int {
            val fromInfo = parsed.dataInfo?.numPages ?: 0
            if (fromInfo > 0) return fromInfo
            val details = parsed.layoutDetails as? JsonArray ?: return 0
            if (details.isEmpty()) return 0
            return if (details.any { it is JsonArray }) details.size else 1
        }

        internal fun applyResponse(
            parsed: ZaiLayoutParsingResponseDto,
            includeImages: Boolean,
            images: DocumentImageWriter,
            download: (String) -> ByteArray?,
            pageOffset: Int = 0,
        ): String {
            var markdown = parsed.mdResults.trim()
            if (markdown.isEmpty()) {
                throw ZaiQuotaException("Z.ai OCR returned no markdown.")
            }
            if (includeImages) {
                val details = parsed.layoutDetails as? JsonArray ?: JsonArray(emptyList())
                val pageGroups = if (details.any { it is JsonArray }) details else listOf(details)
                pageGroups.forEachIndexed { pageIndex, group ->
                    val pageNumber = pageOffset + pageIndex + 1
                    (group as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                        .filter { (it["label"] as? JsonPrimitive)?.contentOrNull == "image" }
                        .forEachIndexed { index, item ->
                            val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                            if (content.isEmpty()) return@forEachIndexed
                            val box = (item["bbox_2d"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                            val region = box?.takeIf { it.size == 4 }?.let {
                                PdfFigureRegion(
                                    pageNumber, it[0], it[1], it[2], it[3],
                                    (item["width"] as? JsonPrimitive)?.doubleOrNull,
                                    (item["height"] as? JsonPrimitive)?.doubleOrNull,
                                )
                            }
                            val link = images.write(pageNumber, index + 1, region) {
                                decodeImage(content, download)?.let { bytes ->
                                    ProviderDocumentImage(bytes, suggestedName(content, index).substringAfterLast('.'))
                                }
                            }
                            markdown = rewriteMarkdownImageLinks(markdown, mapOf(content to link))
                        }
                }
            }
            return markdown
        }

        internal fun imageFileName(id: String): String? {
            val name = Path.of(id.trim()).fileName.toString()
            return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
        }

        private fun decodeImage(content: String, download: (String) -> ByteArray?): ByteArray? {
            if (content.startsWith("data:")) {
                val encoded = content.substringAfter("base64,", "").trim()
                if (encoded.isEmpty()) return null
                return runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            }
            if (content.startsWith("http://") || content.startsWith("https://")) {
                return download(content)
            }
            return null
        }

        private fun suggestedName(content: String, index: Int): String {
            if (content.startsWith("data:")) {
                val mime = content.substringAfter("data:").substringBefore(";").substringBefore(",")
                val ext = when (mime) {
                    "image/jpeg" -> "jpg"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "png"
                }
                return "img-$index.$ext"
            }
            val path = runCatching { URI.create(content).path }.getOrNull().orEmpty()
            val base = imageFileName(path.substringAfterLast('/').substringBefore('?'))
            if (base != null && '.' in base) return base
            return "img-$index.png"
        }

        private fun mimeType(path: Path, bytes: ByteArray): String {
            if (bytes.size >= 5 && bytes.decodeToString(0, 5) == "%PDF-") return "application/pdf"
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) return "image/png"
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            return when (path.fileName.toString().substringAfterLast('.', "").lowercase()) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "application/octet-stream"
            }
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class ZaiLayoutParsingRequestDto(
    val model: String,
    val file: String,
    @SerialName("return_crop_images") val returnCropImages: Boolean = false,
)

@Serializable
internal data class ZaiLayoutParsingResponseDto(
    @SerialName("md_results") val mdResults: String = "",
    @SerialName("layout_details") val layoutDetails: JsonElement? = null,
    @SerialName("data_info") val dataInfo: ZaiOcrDataInfoDto? = null,
)

@Serializable
internal data class ZaiOcrDataInfoDto(
    @SerialName("num_pages") val numPages: Int = 0,
)

internal data class ZaiHttpResult(val status: Int, val body: String)

@Serializable
internal data class ZaiOcrWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("image_files") val imageFiles: List<String> = emptyList(),
    val pages: Int,
    val warnings: List<String> = emptyList(),
)
