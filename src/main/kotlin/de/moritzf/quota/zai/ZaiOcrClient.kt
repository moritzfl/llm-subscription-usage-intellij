package de.moritzf.quota.zai

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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
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
    open fun convertDocument(
        apiKey: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_MODEL,
        imageOptions: DocumentImageOptions = DocumentImageOptions(),
    ): String {
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val file = resolveFile(documentUrl, localFile)
        val markdownOutput = outputFile ?: defaultMarkdownOutput(localFile)
        val writeImages = includeImages && markdownOutput != null
        val body = JsonSupport.json.encodeToString(
            ZaiLayoutParsingRequestDto(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                file = file,
                returnCropImages = writeImages,
            ),
        )
        val response = send(postJson(token, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("Z.ai OCR failed (HTTP $status). Try again later.", status, responseBody)
        }
        if (markdownOutput == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        val written = writeMarkdown(responseBody, markdownOutput, writeImages, imageOptions,
            OriginalPdf.source(localFile, documentUrl)) { url -> downloadBytes(url) }
        return JsonSupport.json.encodeToString(written)
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

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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
            download: (String) -> ByteArray? = { null },
        ): ZaiOcrWriteResult {
            val parsed = try {
                JsonSupport.json.decodeFromString<ZaiLayoutParsingResponseDto>(responseBody)
            } catch (exception: Exception) {
                throw ZaiQuotaException("Could not parse OCR response.", 200, responseBody, exception)
            }
            var markdown = parsed.mdResults.trim()
            if (markdown.isEmpty()) {
                throw ZaiQuotaException("Z.ai OCR returned no markdown.", 200, responseBody)
            }
            DocumentImageWriter(outputFile, imageOptions, originalPdf).use { images ->
                if (includeImages) {
                    val details = parsed.layoutDetails as? JsonArray ?: JsonArray(emptyList())
                    val pageGroups = if (details.any { it is JsonArray }) details else listOf(details)
                    pageGroups.forEachIndexed { pageIndex, group ->
                        (group as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                            .filter { (it["label"] as? JsonPrimitive)?.contentOrNull == "image" }
                            .forEachIndexed { index, item ->
                                val content = (item["content"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                                if (content.isEmpty()) return@forEachIndexed
                                val box = (item["bbox_2d"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                                val region = box?.takeIf { it.size == 4 }?.let {
                                    PdfFigureRegion(pageIndex + 1, it[0], it[1], it[2], it[3],
                                        (item["width"] as? JsonPrimitive)?.doubleOrNull,
                                        (item["height"] as? JsonPrimitive)?.doubleOrNull)
                                }
                                val link = images.write(pageIndex + 1, index + 1, region) {
                                    decodeImage(content, download)?.let { bytes ->
                                        ProviderDocumentImage(bytes, suggestedName(content, index).substringAfterLast('.'))
                                    }
                                }
                                markdown = rewriteMarkdownImageLinks(markdown, mapOf(content to link))
                            }
                    }
                }
                DocumentMarkdown.writeAtomically(outputFile, markdown)
                images.commit()
                return ZaiOcrWriteResult(outputFile.toString(), images.imageFiles, parsed.dataInfo?.numPages ?: 0, images.warnings)
            }
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

@Serializable
internal data class ZaiOcrWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("image_files") val imageFiles: List<String> = emptyList(),
    val pages: Int,
    val warnings: List<String> = emptyList(),
)
