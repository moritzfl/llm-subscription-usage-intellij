package de.moritzf.quota.zai

import de.moritzf.quota.shared.DefaultOutputFiles
import de.moritzf.quota.shared.HttpJsonUrls
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
import java.util.Locale
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

open class ZaiVideoClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val generationsUri: URI = GENERATIONS_URI,
    private val resultBaseUri: URI = RESULT_BASE_URI,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    open fun generateVideo(
        apiKey: String,
        prompt: String,
        model: String = DEFAULT_MODEL,
        imageUrl: String? = null,
        waitForCompletion: Boolean = true,
        pollTimeoutSeconds: Int = DEFAULT_POLL_TIMEOUT_SECONDS,
        targetFile: String? = null,
        baseDirectory: Path? = null,
    ): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            throw ZaiQuotaException("Video prompt is required.")
        }
        val token = apiKey.trim().ifBlank {
            throw ZaiQuotaException("Z.ai API key missing. Add a Z.ai API key in settings.")
        }
        val body = JsonSupport.json.encodeToString(
            ZaiVideoRequestDto(
                model = model.trim().ifBlank { DEFAULT_MODEL },
                prompt = trimmedPrompt,
                imageUrl = imageUrl?.trim()?.takeIf { it.isNotEmpty() },
            ),
        )
        val start = send(postJson(token, generationsUri, body))
        val startBody = start.body()
        if (start.statusCode() == 401 || start.statusCode() == 403) {
            throw ZaiQuotaException("API key invalid. Check your Z.ai API key.", start.statusCode(), startBody)
        }
        if (start.statusCode() !in 200..299) {
            throw ZaiQuotaException("Z.ai video generation failed (HTTP ${start.statusCode()}). Try again later.", start.statusCode(), startBody)
        }
        if (!waitForCompletion) {
            return McpJson.providerJsonOrRaw(startBody)
        }
        val id = taskId(startBody)
            ?: throw ZaiQuotaException("Z.ai video generation returned no task id.", 200, startBody)
        val json = poll(token, id, pollTimeoutSeconds.coerceIn(5, MAX_POLL_TIMEOUT_SECONDS))
        return writeVideoIfRequested(json, targetFile, baseDirectory)
    }

    private fun writeVideoIfRequested(body: String, targetFile: String?, baseDirectory: Path?): String {
        val output = resolveVideoOutput(targetFile, baseDirectory) ?: return body
        val url = HttpJsonUrls.first(body)
            ?: throw ZaiQuotaException("Z.ai video generation returned no video URL.", 200, body)
        val bytes = download(url)
        val parent = output.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        Files.write(output, bytes)
        return buildJsonObject {
            put("output_file", output.toString())
            put("bytes", bytes.size.toLong())
        }.toString()
    }

    private fun resolveVideoOutput(targetFile: String?, baseDirectory: Path?): Path? {
        return DefaultOutputFiles.resolveInsideBase(targetFile, baseDirectory, null)
    }

    private fun download(url: String): ByteArray {
        val response = try {
            httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(180))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        } catch (exception: IOException) {
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ZaiQuotaException("Request failed. Check your connection.", 0, null, exception)
        }
        if (response.statusCode() !in 200..299) {
            throw ZaiQuotaException("Z.ai video download failed (HTTP ${response.statusCode()}).", response.statusCode())
        }
        return response.body()
    }

    private fun poll(token: String, id: String, timeoutSeconds: Int): String {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var lastBody: String? = null
        while (System.currentTimeMillis() < deadline) {
            val response = send(
                HttpRequest.newBuilder()
                    .uri(resultBaseUri.resolve(id))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
            )
            lastBody = response.body()
            val status = response.statusCode()
            if (status == 401 || status == 403) {
                throw ZaiQuotaException("Z.ai video poll failed (HTTP $status).", status, lastBody)
            }
            if (status !in 200..299) {
                throw ZaiQuotaException("Z.ai video poll failed (HTTP $status).", status, lastBody)
            }
            when (taskStatus(lastBody)?.uppercase(Locale.ROOT)) {
                "SUCCESS" -> return McpJson.providerJsonOrRaw(lastBody)
                "FAIL", "FAILED", "ERROR" ->
                    throw ZaiQuotaException("Z.ai video generation failed.", 200, lastBody)
                else -> sleeper(POLL_INTERVAL_MS)
            }
        }
        throw ZaiQuotaException("Z.ai video generation timed out.", 0, lastBody)
    }

    private fun postJson(token: String, uri: URI, body: String): HttpRequest {
        return HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
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

    companion object {
        const val DEFAULT_MODEL = "cogvideox-3"
        const val DEFAULT_POLL_TIMEOUT_SECONDS = 180
        private const val MAX_POLL_TIMEOUT_SECONDS = 600
        private const val POLL_INTERVAL_MS = 3_000L
        private val GENERATIONS_URI = URI.create("https://api.z.ai/api/paas/v4/videos/generations")
        private val RESULT_BASE_URI = URI.create("https://api.z.ai/api/paas/v4/async-result/")

        fun createDefault(): ZaiVideoClient = ZaiVideoClient()

        internal fun taskId(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            return (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (root["request_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        internal fun taskStatus(body: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            return (root["task_status"] as? JsonPrimitive)?.contentOrNull
        }

        private fun defaultHttpClient(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    }
}

@Serializable
internal data class ZaiVideoRequestDto(
    val model: String,
    val prompt: String,
    @kotlinx.serialization.SerialName("image_url") val imageUrl: String? = null,
)
