package de.moritzf.quota.zai

import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

open class ZaiImageClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val generationsUri: URI = GENERATIONS_URI,
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
            throw ZaiQuotaException("Image prompt is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val body = JsonSupport.json.encodeToString(
            ZaiImageRequestDto(model = model.trim().ifBlank { DEFAULT_MODEL }, prompt = trimmedPrompt),
        )
        val response = sendString(postJson(token, body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw ZaiQuotaException("Z.ai image generation failed (HTTP $status). Try again later.", status, responseBody)
        }
        val output = resolveOutput(targetFile, baseDirectory)
        if (output == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        val url = firstImageUrl(responseBody)
            ?: throw ZaiQuotaException("Z.ai image generation returned no image URL.", status, responseBody)
        val bytes = download(url)
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(output, bytes)
        return JsonSupport.json.encodeToString(ZaiImageWriteResult(output.toString(), bytes.size.toLong()))
    }

    private fun download(url: String): ByteArray {
        val response = try {
            httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } catch (exception: IOException) {
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
        if (response.statusCode() !in 200..299) {
            throw ZaiQuotaException("Z.ai image download failed (HTTP ${response.statusCode()}).", response.statusCode())
        }
        return response.body()
    }

    private fun sendString(request: HttpRequest): HttpResponse<String> {
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
            .uri(generationsUri)
            .timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    }

    companion object {
        const val DEFAULT_MODEL = "glm-image"
        private val GENERATIONS_URI = URI.create("https://api.z.ai/api/paas/v4/images/generations")

        fun createDefault(): ZaiImageClient = ZaiImageClient()

        internal fun firstImageUrl(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val data = root["data"] as? JsonArray ?: return null
            val first = data.firstOrNull() as? JsonObject ?: return null
            return (first["url"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        internal fun resolveOutput(targetFile: String?, baseDirectory: Path?): Path? {
            return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, null)
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class ZaiImageRequestDto(
    val model: String,
    val prompt: String,
)

@Serializable
internal data class ZaiImageWriteResult(
    @SerialName("output_file") val outputFile: String,
    val bytes: Long,
)
