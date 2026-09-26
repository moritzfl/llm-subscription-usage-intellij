package de.moritzf.quota.supergrok

import de.moritzf.quota.openai.proxy.pdf.PdfPages
import de.moritzf.quota.shared.DocumentLimits
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.MultipartFilePublisher
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

open class SuperGrokDocumentClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
) {
    open fun convertDocument(
        accessToken: String,
        documentUrl: String? = null,
        localFile: Path? = null,
        outputFile: Path? = null,
        includeImages: Boolean = true,
        model: String = DEFAULT_MODEL,
        pageFrom: Int? = null,
        pageTo: Int? = null,
    ): String {
        val token = accessToken.trim().ifBlank {
            throw SuperGrokQuotaException("Grok login required. Log in from SuperGrok settings.")
        }
        if (pageFrom != null || pageTo != null) {
            if (localFile == null || !PdfPages.isPdf(localFile)) {
                throw SuperGrokQuotaException("pageFrom and pageTo require a local PDF.")
            }
        }
        val range = if (localFile != null && PdfPages.isPdf(localFile)) {
            val count = PdfPages.pageCount(localFile)
            if (count == null) {
                if (pageFrom != null || pageTo != null) {
                    throw SuperGrokQuotaException("Could not read PDF page count.")
                }
                null
            } else {
                PdfPages.resolve(count, pageFrom, pageTo)
                    ?: throw SuperGrokQuotaException("pageFrom/pageTo out of range (document has $count pages).")
            }
        } else {
            null
        }
        val slice = range?.takeUnless { it.isFullDocument }?.let { pages ->
            val temp = Files.createTempFile("quota-pdf-slice", ".pdf")
            if (!PdfPages.writeSlice(localFile!!, pages.from, pages.to, temp)) {
                Files.deleteIfExists(temp)
                throw SuperGrokQuotaException("Could not extract the requested PDF page range.")
            }
            temp
        }
        val sendFile = slice ?: localFile
        val markdownOutput = outputFile ?: DocumentMarkdown.defaultOutput(localFile)
        val uploadedId = if (documentUrl.isNullOrBlank() && sendFile != null) {
            uploadFile(token, sendFile)
        } else {
            null
        }
        return try {
            val fileContent = documentInput(documentUrl, sendFile, uploadedId)
            val response = send(postJson(token, requestJson(model.trim().ifBlank { DEFAULT_MODEL }, fileContent)))
            val status = response.statusCode()
            val body = response.body()
            if (status == 401 || status == 403) {
                throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status, body)
            }
            if (status !in 200..299) {
                throw SuperGrokQuotaException("Grok document conversion failed (HTTP $status). Try again later.", status, body)
            }
            val applied = de.moritzf.quota.idea.mcp.DocumentImageGrounding.apply(
                parseMarkdown(body),
                if (includeImages) localFile else null,
                markdownOutput?.parent ?: localFile?.parent,
                includeImages,
                pageOffset = range?.offset ?: 0,
            )
            DocumentMarkdown.resultJson(
                applied.markdown,
                markdownOutput,
                applied.imageFiles,
                range?.pageCount,
                range?.from,
                range?.to,
            )
        } finally {
            uploadedId?.let { runCatching { deleteFile(token, it) } }
            slice?.let { Files.deleteIfExists(it) }
        }
    }

    private fun uploadFile(token: String, path: Path): String {
        if (!Files.isRegularFile(path)) {
            throw SuperGrokQuotaException("Local document was not found.")
        }
        val boundary = "----GrokDoc${UUID.randomUUID().toString().replace("-", "")}"
        val response = send(
            HttpRequest.newBuilder()
                .uri(baseUri.resolve(FILES_PATH))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .header("User-Agent", USER_AGENT)
                .POST(
                    MultipartFilePublisher.of(
                        boundary,
                        listOf("expires_after" to "3600", "purpose" to "assistants"),
                        path,
                    ),
                )
                .build(),
        )
        if (response.statusCode() !in 200..299) {
            throw SuperGrokQuotaException(
                "Grok file upload failed (HTTP ${response.statusCode()}).",
                response.statusCode(),
                response.body(),
            )
        }
        val id = (runCatching { JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
            ?.get("id") as? JsonPrimitive)?.contentOrNull
        if (id.isNullOrBlank()) {
            throw SuperGrokQuotaException("Grok file upload returned no id.", response.statusCode(), response.body())
        }
        return id
    }

    private fun deleteFile(token: String, fileId: String) {
        send(
            HttpRequest.newBuilder()
                .uri(baseUri.resolve("$FILES_PATH/$fileId"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .DELETE()
                .build(),
        )
    }

    private fun postJson(token: String, body: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(baseUri.resolve(RESPONSES_PATH))
            .timeout(Duration.ofSeconds(180))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw SuperGrokQuotaException("Grok document request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException("Grok document request failed. Check your connection.", 0, null, exception)
        }
    }

    companion object {
        const val DEFAULT_MODEL = de.moritzf.quota.shared.DocumentModels.SUPERGROK_DEFAULT
        private const val RESPONSES_PATH = "responses"
        private const val FILES_PATH = "files"
        private const val USER_AGENT = "openai-usage-quota-intellij"
        private val DEFAULT_BASE_URI = URI.create("https://api.x.ai/v1/")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private const val DOCUMENT_PROMPT = de.moritzf.quota.idea.mcp.DocumentImageGrounding.PROMPT

        fun createDefault(): SuperGrokDocumentClient = SuperGrokDocumentClient()

        internal fun documentInput(documentUrl: String?, localFile: Path?, fileId: String?): JsonObject {
            val url = documentUrl?.trim().orEmpty()
            if (url.isNotEmpty()) {
                return if (isImageName(url)) {
                    buildJsonObject {
                        put("type", "input_image")
                        put("image_url", url)
                    }
                } else {
                    buildJsonObject {
                        put("type", "input_file")
                        put("file_url", url)
                    }
                }
            }
            if (!fileId.isNullOrBlank()) {
                return buildJsonObject {
                    put("type", "input_file")
                    put("file_id", fileId)
                }
            }
            val path = localFile ?: throw SuperGrokQuotaException("Provide documentUrl or a local file path.")
            if (!Files.isRegularFile(path)) {
                throw SuperGrokQuotaException("Local document was not found.")
            }
            DocumentLimits.inlineOverflowMessage(path)?.let { throw SuperGrokQuotaException(it) }
            val bytes = Files.readAllBytes(path)
            val mime = mimeType(path, bytes)
            val dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
            return if (mime.startsWith("image/")) {
                buildJsonObject {
                    put("type", "input_image")
                    put("image_url", dataUrl)
                }
            } else {
                buildJsonObject {
                    put("type", "input_file")
                    put("filename", path.fileName.toString())
                    put("file_data", dataUrl)
                }
            }
        }

        internal fun requestJson(model: String, fileContent: JsonObject): String {
            return buildJsonObject {
                put("model", model)
                putJsonArray("input") {
                    add(buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(fileContent)
                            add(buildJsonObject {
                                put("type", "input_text")
                                put("text", DOCUMENT_PROMPT)
                            })
                        }
                    })
                }
            }.toString()
        }

        internal fun parseMarkdown(responseBody: String): String {
            val root = runCatching { JsonSupport.json.parseToJsonElement(responseBody) }.getOrNull() as? JsonObject
                ?: throw SuperGrokQuotaException("Grok document response changed.", 200, responseBody)
            val texts = mutableListOf<String>()
            fun walk(element: JsonElement?) {
                when (element) {
                    is JsonObject -> {
                        val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                        val text = (element["text"] as? JsonPrimitive)?.contentOrNull
                        if ((type == "output_text" || type == "text") && !text.isNullOrBlank()) {
                            texts += text
                        }
                        element.values.forEach(::walk)
                    }
                    is JsonArray -> element.forEach(::walk)
                    else -> Unit
                }
            }
            walk(root["output"] ?: root)
            val markdown = texts.joinToString("").trim()
            if (markdown.isEmpty()) {
                throw SuperGrokQuotaException("Grok document conversion returned no output.", 200, responseBody)
            }
            return markdown
        }

        private fun isImageName(value: String): Boolean {
            val name = value.substringAfterLast('/').substringBefore('?').lowercase(Locale.ROOT)
            return name.substringAfterLast('.', "") in IMAGE_EXTENSIONS
        }

        private fun mimeType(path: Path, bytes: ByteArray): String {
            if (bytes.size >= 5 && bytes.decodeToString(0, 5) == "%PDF-") return "application/pdf"
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) return "image/png"
            if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            return when (path.fileName.toString().substringAfterLast('.', "").lowercase(Locale.ROOT)) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                else -> "application/octet-stream"
            }
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}
