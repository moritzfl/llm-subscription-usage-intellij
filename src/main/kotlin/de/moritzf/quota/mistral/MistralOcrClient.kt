package de.moritzf.quota.mistral

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.shared.MultipartFilePublisher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

open class MistralOcrClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val ocrUri: URI = OCR_URI,
    private val filesUri: URI = FILES_URI,
) {
    open fun convertDocument(
        apiKey: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_MODEL,
    ): String {
        val token = apiKey.trim().ifBlank {
            throw MistralQuotaException("Mistral API key missing. Add a Mistral API key in settings.")
        }
        val document = resolveDocument(token, documentUrl, localFile)
        val markdownOutput = outputFile ?: defaultMarkdownOutput(localFile)
        val writeImages = includeImages && markdownOutput != null
        val body = ocrRequestJson(
            model = model.trim().ifBlank { DEFAULT_MODEL },
            document = document,
            includeImageBase64 = writeImages,
        )
        val response = sendString(postJson(token, ocrUri, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw httpError("Mistral OCR failed", status, responseBody)
        }
        if (markdownOutput == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        val written = writeMarkdown(responseBody, markdownOutput, writeImages)
        return JsonSupport.json.encodeToString(written)
    }

    private fun resolveDocument(apiKey: String, documentUrl: String?, localFile: Path?): MistralOcrDocumentDto {
        val url = documentUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            return MistralOcrDocumentDto(type = "document_url", documentUrl = url)
        }
        val path = localFile ?: throw MistralQuotaException("Provide documentUrl or a local file path.")
        if (!Files.isRegularFile(path)) {
            throw MistralQuotaException("Local document was not found.")
        }
        val fileId = uploadFile(apiKey, path)
        return MistralOcrDocumentDto(type = "file", fileId = fileId)
    }

    private fun uploadFile(apiKey: String, path: Path): String {
        val boundary = "----MistralOcr${UUID.randomUUID().toString().replace("-", "")}"
        val request = HttpRequest.newBuilder()
            .uri(filesUri)
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(MultipartFilePublisher.of(boundary, listOf("purpose" to "ocr"), path))
            .build()
        val response = sendString(request)
        if (response.statusCode() !in 200..299) {
            throw httpError("Mistral file upload failed", response.statusCode(), response.body())
        }
        val root = runCatching { JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
        val id = (root?.get("id") as? JsonPrimitive)?.contentOrNull
        if (id.isNullOrBlank()) {
            throw MistralQuotaException("Mistral file upload returned no id.", response.statusCode(), response.body())
        }
        return id
    }

    private fun sendString(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        const val DEFAULT_MODEL = "mistral-ocr-latest"
        private val OCR_URI = URI.create("https://api.mistral.ai/v1/ocr")
        private val FILES_URI = URI.create("https://api.mistral.ai/v1/files")

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
                ),
            )
        }

        internal fun defaultMarkdownOutput(localFile: Path?): Path? {
            if (localFile == null) return null
            val name = localFile.fileName.toString()
            val stem = name.substringBeforeLast('.', name).ifBlank { name }
            return localFile.resolveSibling("$stem.md")
        }

        internal fun mistralErrorDetail(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                ?: return null
            (root["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            when (val error = root["error"]) {
                is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                is JsonObject ->
                    (error["message"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }

                else -> Unit
            }
            when (val detail = root["detail"]) {
                is JsonPrimitive -> detail.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                is JsonArray -> {
                    val first = detail.firstOrNull() as? JsonObject
                    (first?.get("msg") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
                }

                else -> Unit
            }
            return null
        }

        private fun httpError(prefix: String, status: Int, body: String): MistralQuotaException {
            val detail = mistralErrorDetail(body)
            val message = if (detail.isNullOrBlank()) {
                "$prefix (HTTP $status). Try again later."
            } else {
                "$prefix (HTTP $status): $detail"
            }
            return MistralQuotaException(message, status, body)
        }

        internal fun writeMarkdown(
            responseBody: String,
            outputFile: Path,
            includeImages: Boolean
        ): MistralOcrWriteResult {
            val parsed = try {
                JsonSupport.json.decodeFromString<MistralOcrResponseDto>(responseBody)
            } catch (exception: Exception) {
                throw MistralQuotaException("Could not parse OCR response.", 200, responseBody, exception)
            }
            return MistralMarkdownWriter(outputFile, includeImages).use { writer ->
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
internal data class MistralOcrResponseDto(
    val pages: List<MistralOcrPageDto> = emptyList(),
)

@Serializable
internal data class MistralOcrPageDto(
    val markdown: String = "",
    val images: List<MistralOcrImageDto>? = emptyList(),
)

@Serializable
internal data class MistralOcrImageDto(
    val id: String = "",
    @SerialName("image_base64") val imageBase64: String? = null,
)

@Serializable
internal data class MistralOcrWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("image_files") val imageFiles: List<String>,
    val pages: Int,
)
