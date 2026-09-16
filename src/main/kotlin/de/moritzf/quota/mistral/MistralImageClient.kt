package de.moritzf.quota.mistral

import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

open class MistralImageClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val conversationsUri: URI = CONVERSATIONS_URI,
    private val filesBaseUri: URI = FILES_BASE_URI,
) {
    open fun generateImage(
        apiKey: String,
        prompt: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
        model: String = DEFAULT_MODEL,
    ): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            throw MistralQuotaException("Image prompt is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw MistralQuotaException("Mistral API key missing. Add a Mistral API key in settings.")
        }
        val body = JsonSupport.json.encodeToString(
            MistralConversationRequestDto(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                inputs = trimmedPrompt,
                tools = listOf(MistralBuiltInToolDto("image_generation")),
            ),
        )
        val response = sendString(postJson(token, conversationsUri, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MistralQuotaException("Session expired. Check your Mistral API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw MistralQuotaException("Mistral image generation failed (HTTP $status). Try again later.", status, responseBody)
        }
        val output = resolveOutput(targetFile, baseDirectory)
        if (output == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        val fileId = firstToolFileId(responseBody)
            ?: throw MistralQuotaException("Mistral image generation returned no file.", status, responseBody)
        val bytes = downloadFile(token, fileId)
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(output, bytes)
        return JsonSupport.json.encodeToString(
            MistralImageWriteResult(output.toString(), fileId, bytes.size.toLong()),
        )
    }

    private fun downloadFile(apiKey: String, fileId: String): ByteArray {
        val request = HttpRequest.newBuilder()
            .uri(filesBaseUri.resolve("$fileId/content"))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer $apiKey")
            .GET()
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (exception: IOException) {
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MistralQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
        if (response.statusCode() !in 200..299) {
            throw MistralQuotaException(
                "Mistral file download failed (HTTP ${response.statusCode()}).",
                response.statusCode(),
            )
        }
        return response.body()
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
        const val DEFAULT_MODEL = "mistral-medium-latest"
        private val CONVERSATIONS_URI = URI.create("https://api.mistral.ai/v1/conversations")
        private val FILES_BASE_URI = URI.create("https://api.mistral.ai/v1/files/")

        fun createDefault(): MistralImageClient = MistralImageClient()

        internal fun resolveOutput(targetFile: String?, baseDirectory: Path?): Path? {
            return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, DefaultOutputFiles.image())
        }

        internal fun firstToolFileId(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) }.getOrNull() ?: return null
            return findFileId(root)
        }

        private fun findFileId(element: JsonElement): String? {
            return when (element) {
                is JsonObject -> {
                    val type = (element["type"] as? JsonPrimitive)?.contentOrNull
                    val fileId = (element["file_id"] as? JsonPrimitive)?.contentOrNull
                    if (type == "tool_file" && !fileId.isNullOrBlank()) {
                        return fileId
                    }
                    element.values.firstNotNullOfOrNull(::findFileId)
                }
                is JsonArray -> element.firstNotNullOfOrNull(::findFileId)
                else -> null
            }
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()

        private fun postJson(apiKey: String, uri: URI, body: String): HttpRequest {
            return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
    }
}

@Serializable
internal data class MistralImageWriteResult(
    @SerialName("output_file") val outputFile: String,
    @SerialName("file_id") val fileId: String,
    val bytes: Long,
)
