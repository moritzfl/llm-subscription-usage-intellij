package de.moritzf.quota.mistral

import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentImageExportReport
import de.moritzf.quota.shared.DocumentImageOptions
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.MultipartFilePublisher
import de.moritzf.quota.shared.OriginalPdf
import de.moritzf.quota.shared.document.PdfFigureRenderer
import de.moritzf.quota.shared.document.PdfOcrSlices
import de.moritzf.quota.shared.document.PdfPages
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.apache.pdfbox.Loader

open class MistralOcrClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val ocrUri: URI = OCR_URI,
    private val filesUri: URI = FILES_URI,
) {
    private var post: ((HttpRequest) -> MistralHttpResult)? = null

    internal constructor(post: (HttpRequest) -> MistralHttpResult) : this() {
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
    ): String =
        convertDocument(
            apiKey,
            documentUrl,
            localFile,
            outputFile,
            includeImages,
            model,
            imageOptions,
            DocumentConversionProgress.NONE,
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
        val token =
            apiKey.trim().ifBlank {
                throw MistralQuotaException(
                    "Mistral API key missing. Add a Mistral API key in settings."
                )
            }
        val selectedModel = model.trim().ifBlank { DEFAULT_MODEL }
        // PDFs that PDFBox cannot read still go to Mistral as one upload.
        if (
            documentUrl.isNullOrBlank() &&
                localFile != null &&
                PdfPages.pageCount(localFile) != null
        ) {
            return convertPdfSlices(
                token,
                localFile,
                outputFile ?: checkNotNull(defaultMarkdownOutput(localFile)),
                includeImages,
                selectedModel,
                imageOptions,
                progress,
            )
        }
        val document = resolveDocument(token, documentUrl, localFile)
        val markdownOutput = outputFile ?: defaultMarkdownOutput(localFile)
        val writeImages = includeImages && markdownOutput != null
        val body =
            ocrRequestJson(
                model = selectedModel,
                document = document,
                includeImageBase64 = writeImages,
            )
        val responseBody = checkedOcrBody(send(postJson(token, ocrUri, body)), "Mistral OCR failed")
        if (markdownOutput == null) {
            return McpJson.providerJson(responseBody)
        }
        val written =
            writeMarkdown(
                responseBody,
                markdownOutput,
                writeImages,
                imageOptions,
                OriginalPdf.source(localFile, documentUrl),
            )
        return JsonSupport.json.encodeToString(written)
    }

    /** Local PDFs go inline in slices, which also keeps uploads off the user's Mistral files. */
    private fun convertPdfSlices(
        token: String,
        localFile: Path,
        output: Path,
        includeImages: Boolean,
        model: String,
        imageOptions: DocumentImageOptions,
        progress: DocumentConversionProgress,
    ): String {
        MistralMarkdownWriter(output, includeImages, imageOptions, OriginalPdf.local(localFile))
            .use { writer ->
                Loader.loadPDF(localFile.toFile()).use { pdf ->
                    val total = pdf.numberOfPages
                    val original =
                        if (
                            total <= MAX_PAGES_PER_REQUEST && Files.size(localFile) <= MAX_PDF_BYTES
                        )
                            Files.readAllBytes(localFile)
                        else null
                    SLICES.convert(pdf, progress, original) { bytes, mime, from, to, lastAttempt ->
                        val detail = "Pages $from–$to of $total"
                        progress.update(from - 1, total, detail)
                        val dataUrl =
                            "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
                        val document =
                            if (mime == PdfOcrSlices.PDF)
                                MistralOcrDocumentDto("document_url", documentUrl = dataUrl)
                            else MistralOcrDocumentDto("image_url", imageUrl = dataUrl)
                        val response =
                            try {
                                send(
                                    postJson(
                                        token,
                                        ocrUri,
                                        ocrRequestJson(model, document, includeImages),
                                    )
                                )
                            } catch (exception: MistralQuotaException) {
                                if (lastAttempt || exception.cause !is HttpTimeoutException)
                                    throw exception
                                return@convert false
                            }
                        progress.update(from - 1, total, detail)
                        if (!lastAttempt && response.status in 500..599) return@convert false
                        val body =
                            checkedOcrBody(response, "Mistral OCR failed for pages $from–$to")
                        val pages =
                            try {
                                JsonSupport.json.decodeFromString<MistralOcrResponseDto>(body).pages
                            } catch (exception: Exception) {
                                throw MistralQuotaException(
                                    "Mistral OCR returned invalid page data for pages $from–$to.",
                                    200,
                                    body,
                                    exception,
                                )
                            }
                        if (pages.size != to - from + 1) {
                            throw MistralQuotaException(
                                "Mistral OCR returned ${pages.size} pages for pages $from–$to; output was not saved.",
                                200,
                                body,
                            )
                        }
                        writer.append(pages, from - 1)
                        progress.update(to, total, "Converted $to of $total pages")
                        true
                    }
                }
                return JsonSupport.json.encodeToString(writer.commit())
            }
    }

    private fun checkedOcrBody(response: MistralHttpResult, prefix: String): String {
        if (response.status == 401 || response.status == 403) {
            throw MistralQuotaException(
                "Session expired. Check your Mistral API key.",
                response.status,
                response.body,
            )
        }
        if (response.status !in 200..299) throw httpError(prefix, response.status, response.body)
        return response.body
    }

    private fun resolveDocument(
        apiKey: String,
        documentUrl: String?,
        localFile: Path?,
    ): MistralOcrDocumentDto {
        val url = documentUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            return MistralOcrDocumentDto(type = "document_url", documentUrl = url)
        }
        val path =
            localFile ?: throw MistralQuotaException("Provide documentUrl or a local file path.")
        if (!Files.isRegularFile(path)) {
            throw MistralQuotaException("Local document was not found.")
        }
        val fileId = uploadFile(apiKey, path)
        return MistralOcrDocumentDto(type = "file", fileId = fileId)
    }

    private fun uploadFile(apiKey: String, path: Path): String {
        val boundary = "----MistralOcr${UUID.randomUUID().toString().replace("-", "")}"
        val request =
            HttpRequest.newBuilder()
                .uri(filesUri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(MultipartFilePublisher.of(boundary, listOf("purpose" to "ocr"), path))
                .build()
        val response = send(request)
        if (response.status !in 200..299) {
            throw httpError("Mistral file upload failed", response.status, response.body)
        }
        val root = runCatching {
            JsonSupport.json.parseToJsonElement(response.body) as? JsonObject
        }
            .getOrNull()
        val id = (root?.get("id") as? JsonPrimitive)?.contentOrNull
        if (id.isNullOrBlank()) {
            throw MistralQuotaException(
                "Mistral file upload returned no id.",
                response.status,
                response.body,
            )
        }
        return id
    }

    private fun send(request: HttpRequest): MistralHttpResult {
        return try {
            post?.invoke(request)
                ?: httpClient.send(request, HttpResponse.BodyHandlers.ofString()).let {
                    MistralHttpResult(it.statusCode(), it.body())
                }
        } catch (exception: IOException) {
            throw MistralQuotaException(
                "Request failed. Check your connection.",
                0,
                null,
                exception,
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException(
                "Request failed. Check your connection.",
                0,
                null,
                exception,
            )
        }
    }

    companion object {
        const val DEFAULT_MODEL = "mistral-ocr-latest"
        private val OCR_URI = URI.create("https://api.mistral.ai/v1/ocr")
        private val FILES_URI = URI.create("https://api.mistral.ai/v1/files")
        // The API allows 1000 pages and 50 MB; small slices keep requests under the read timeout.
        private const val MAX_PAGES_PER_REQUEST = 30
        private const val MAX_PDF_BYTES = 50L * 1024 * 1024
        private val SLICES =
            PdfOcrSlices("Mistral OCR", MAX_PAGES_PER_REQUEST, MAX_PDF_BYTES) {
                MistralQuotaException(it)
            }

        fun createDefault(): MistralOcrClient = MistralOcrClient()

        internal fun imageFileName(id: String): String? {
            val name = id.trim().replace('\\', '/').substringAfterLast('/')
            return name.takeIf { it.isNotBlank() && it != "." && it != ".." }
        }

        internal fun ocrRequestJson(
            model: String,
            document: MistralOcrDocumentDto,
            includeImageBase64: Boolean,
        ): String {
            return JsonSupport.json.encodeToString(
                MistralOcrRequestDto(
                    model = model,
                    document = document,
                    includeImageBase64 = includeImageBase64,
                    includeBlocks = false,
                )
            )
        }

        internal fun defaultMarkdownOutput(localFile: Path?): Path? {
            if (localFile == null) return null
            val name = localFile.fileName.toString()
            val stem = name.substringBeforeLast('.', name).ifBlank { name }
            return localFile.resolveSibling("$stem.md")
        }

        internal fun mistralErrorDetail(body: String): String? {
            val root =
                runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    ?: return null
            (root["message"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    return it
                }
            when (val error = root["error"]) {
                is JsonPrimitive ->
                    error.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            return it
                        }
                is JsonObject ->
                    (error["message"] as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            return it
                        }

                else -> Unit
            }
            when (val detail = root["detail"]) {
                is JsonPrimitive ->
                    detail.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            return it
                        }
                is JsonArray -> {
                    val first = detail.firstOrNull() as? JsonObject
                    (first?.get("msg") as? JsonPrimitive)
                        ?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            return it
                        }
                }

                else -> Unit
            }
            return null
        }

        private fun httpError(prefix: String, status: Int, body: String): MistralQuotaException {
            val detail = mistralErrorDetail(body)
            val message =
                if (detail.isNullOrBlank()) {
                    "$prefix (HTTP $status). Try again later."
                } else {
                    "$prefix (HTTP $status): $detail"
                }
            return MistralQuotaException(message, status, body)
        }

        internal fun writeMarkdown(
            responseBody: String,
            outputFile: Path,
            includeImages: Boolean,
            imageOptions: DocumentImageOptions = DocumentImageOptions(),
            originalPdf: () -> PdfFigureRenderer? = { null },
        ): MistralOcrWriteResult {
            val parsed =
                try {
                    JsonSupport.json.decodeFromString<MistralOcrResponseDto>(responseBody)
                } catch (exception: Exception) {
                    throw MistralQuotaException(
                        "Could not parse OCR response.",
                        200,
                        responseBody,
                        exception,
                    )
                }
            return MistralMarkdownWriter(outputFile, includeImages, imageOptions, originalPdf)
                .use { writer ->
                    writer.append(parsed.pages)
                    writer.commit()
                }
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        private fun postJson(apiKey: String, uri: URI, body: String): HttpRequest {
            return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
    }
}

internal data class MistralHttpResult(val status: Int, val body: String)

@Serializable
internal data class MistralOcrRequestDto(
    val model: String,
    val document: MistralOcrDocumentDto,
    @SerialName("include_image_base64") val includeImageBase64: Boolean = false,
    @SerialName("include_blocks") val includeBlocks: Boolean? = null,
)

@Serializable
internal data class MistralOcrDocumentDto(
    val type: String,
    @SerialName("document_url") val documentUrl: String? = null,
    @SerialName("file_id") val fileId: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
internal data class MistralOcrResponseDto(val pages: List<MistralOcrPageDto> = emptyList())

@Serializable
internal data class MistralOcrPageDto(
    val markdown: String = "",
    val images: List<MistralOcrImageDto>? = emptyList(),
    val index: Int? = null,
    val dimensions: MistralOcrPageDimensionsDto? = null,
)

@Serializable
internal data class MistralOcrPageDimensionsDto(
    val width: Double? = null,
    val height: Double? = null,
)

@Serializable
internal data class MistralOcrImageDto(
    val id: String = "",
    @SerialName("image_base64") val imageBase64: String? = null,
    @SerialName("top_left_x") val topLeftX: Double? = null,
    @SerialName("top_left_y") val topLeftY: Double? = null,
    @SerialName("bottom_right_x") val bottomRightX: Double? = null,
    @SerialName("bottom_right_y") val bottomRightY: Double? = null,
)

@Serializable
internal data class MistralOcrWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("image_files") val imageFiles: List<String>,
    val pages: Int,
    val warnings: List<String> = emptyList(),
    @SerialName("image_export") val imageExport: DocumentImageExportReport? = null,
)
