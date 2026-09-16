package de.moritzf.quota.minimax

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
import kotlinx.serialization.json.intOrNull

open class MiniMaxImageClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val globalApiHost: URI = GLOBAL_API_HOST,
    private val cnApiHost: URI = CN_API_HOST,
) {
    open fun generateImage(
        apiKey: String,
        region: MiniMaxRegion,
        prompt: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
        model: String = DEFAULT_MODEL,
    ): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            throw MiniMaxQuotaException("Image prompt is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw MiniMaxQuotaException("MiniMax API key missing. Add a MiniMax API key in settings.")
        }
        val body = JsonSupport.json.encodeToString(
            MiniMaxImageRequestDto(model = model.trim().ifBlank { DEFAULT_MODEL }, prompt = trimmedPrompt),
        )
        val response = send(postJson(token, apiHost(region).resolve(IMAGE_PATH), body))
        val status = response.statusCode()
        val responseBody = response.body()
        if (status == 401 || status == 403) {
            throw MiniMaxQuotaException("Session expired. Check your MiniMax API key.", status, responseBody)
        }
        if (status !in 200..299) {
            throw MiniMaxQuotaException("MiniMax image generation failed (HTTP $status). Try again later.", status, responseBody)
        }
        checkBaseResp(responseBody)
        val output = resolveOutput(targetFile, baseDirectory)
        if (output == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        val url = firstImageUrl(responseBody)
            ?: throw MiniMaxQuotaException("MiniMax image generation returned no image URL.", status, responseBody)
        val bytes = download(url)
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(output, bytes)
        return JsonSupport.json.encodeToString(MiniMaxImageWriteResult(output.toString(), bytes.size.toLong()))
    }

    private fun download(url: String): ByteArray {
        val response = try {
            httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } catch (exception: IOException) {
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
        if (response.statusCode() !in 200..299) {
            throw MiniMaxQuotaException("MiniMax image download failed (HTTP ${response.statusCode()}).", response.statusCode())
        }
        return response.body()
    }

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

    private fun send(request: HttpRequest): HttpResponse<String> {
        return try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MiniMaxQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
    }

    private fun apiHost(region: MiniMaxRegion): URI {
        return when (region) {
            MiniMaxRegion.GLOBAL -> globalApiHost
            MiniMaxRegion.CN -> cnApiHost
        }
    }

    companion object {
        const val DEFAULT_MODEL = "image-01"
        private const val IMAGE_PATH = "/v1/image_generation"
        private val GLOBAL_API_HOST = URI.create("https://api.minimax.io")
        private val CN_API_HOST = URI.create("https://api.minimaxi.com")

        fun createDefault(): MiniMaxImageClient = MiniMaxImageClient()

        internal fun firstImageUrl(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val data = root["data"] as? JsonObject ?: return null
            val urls = data["image_urls"] as? JsonArray ?: return null
            return (urls.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        internal fun checkBaseResp(body: String) {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return
            val base = root["base_resp"] as? JsonObject ?: return
            val code = (base["status_code"] as? JsonPrimitive)?.intOrNull ?: 0
            if (code != 0) {
                val msg = (base["status_msg"] as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { code.toString() }
                throw MiniMaxQuotaException("MiniMax image generation failed: $msg", code, body)
            }
        }

        internal fun resolveOutput(targetFile: String?, baseDirectory: Path?): Path? {
            return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, null)
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class MiniMaxImageRequestDto(
    val model: String,
    val prompt: String,
    @SerialName("response_format") val responseFormat: String = "url",
    val n: Int = 1,
)

@Serializable
internal data class MiniMaxImageWriteResult(
    @SerialName("output_file") val outputFile: String,
    val bytes: Long,
)
