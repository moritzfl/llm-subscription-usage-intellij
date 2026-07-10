package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * SuperGrok/xAI Imagine API client for image and video generation over subscription OAuth.
 */
open class SuperGrokImagineClient(
    private val httpClient: HttpClient = defaultHttpClient(),
    private val baseUri: URI = DEFAULT_BASE_URI,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    open fun generateImage(
        accessToken: String,
        prompt: String,
        model: String = DEFAULT_IMAGE_MODEL,
    ): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            throw SuperGrokQuotaException("Image prompt is required.")
        }
        val token = requireToken(accessToken)
        val trimmedModel = model.trim().ifBlank { DEFAULT_IMAGE_MODEL }
        val body = JsonSupport.json.encodeToString(
            GrokImageGenerationRequestDto(
                model = trimmedModel,
                prompt = trimmedPrompt,
                n = 1,
                responseFormat = "url",
            ),
        )
        return McpJson.providerJsonOrRaw(
            postJson(token, IMAGES_GENERATIONS_PATH, body, requestTimeoutSeconds = 120),
        )
    }

    open fun generateVideo(
        accessToken: String,
        prompt: String,
        model: String = DEFAULT_VIDEO_MODEL,
        duration: Int = DEFAULT_VIDEO_DURATION_SECONDS,
        imageUrl: String? = null,
        waitForCompletion: Boolean = true,
        pollTimeoutSeconds: Int = DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS,
    ): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            throw SuperGrokQuotaException("Video prompt is required.")
        }
        val token = requireToken(accessToken)
        val trimmedModel = model.trim().ifBlank { DEFAULT_VIDEO_MODEL }
        val seconds = duration.coerceIn(MIN_VIDEO_DURATION_SECONDS, MAX_VIDEO_DURATION_SECONDS)
        val image = imageUrl?.trim()?.takeIf { it.isNotBlank() }?.let {
            GrokVideoImageDto(url = it)
        }
        val body = JsonSupport.json.encodeToString(
            GrokVideoGenerationRequestDto(
                model = trimmedModel,
                prompt = trimmedPrompt,
                duration = seconds,
                image = image,
            ),
        )
        val startBody = postJson(token, VIDEOS_GENERATIONS_PATH, body, requestTimeoutSeconds = 60)
        if (!waitForCompletion) {
            return McpJson.providerJsonOrRaw(startBody)
        }
        val requestId = extractRequestId(startBody)
            ?: throw SuperGrokQuotaException("Grok video generation did not return a request_id.", 200, startBody)
        return pollVideo(token, requestId, pollTimeoutSeconds.coerceIn(5, MAX_VIDEO_POLL_TIMEOUT_SECONDS))
    }

    private fun pollVideo(accessToken: String, requestId: String, timeoutSeconds: Int): String {
        val deadlineMs = System.currentTimeMillis() + timeoutSeconds * 1000L
        var lastBody: String? = null
        while (System.currentTimeMillis() < deadlineMs) {
            val body = getJson(accessToken, "videos/$requestId", requestTimeoutSeconds = 30)
            lastBody = body
            val status = extractVideoStatus(body)?.lowercase(Locale.ROOT)
            when (status) {
                "done", "completed", "succeeded", "success" -> return McpJson.providerJsonOrRaw(body)
                "failed", "expired", "error" ->
                    throw SuperGrokQuotaException("Grok video generation $status.", 200, body)
                else -> sleeper(VIDEO_POLL_INTERVAL_MS)
            }
        }
        throw SuperGrokQuotaException(
            "Grok video generation timed out after ${timeoutSeconds}s. Last status payload available in raw body.",
            0,
            lastBody,
        )
    }

    private fun postJson(
        accessToken: String,
        path: String,
        body: String,
        requestTimeoutSeconds: Long,
    ): String {
        val request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return sendForBody(request)
    }

    private fun getJson(accessToken: String, path: String, requestTimeoutSeconds: Long): String {
        val request = HttpRequest.newBuilder()
            .uri(baseUri.resolve(path))
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        return sendForBody(request)
    }

    private fun sendForBody(request: HttpRequest): String {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: IOException) {
            throw SuperGrokQuotaException("Grok Imagine request failed. Check your connection.", 0, null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw SuperGrokQuotaException("Grok Imagine request failed. Check your connection.", 0, null, exception)
        }
        val status = response.statusCode()
        val body = response.body()
        if (status == 401 || status == 403) {
            throw SuperGrokQuotaException("Grok auth expired. Log in to SuperGrok again from settings.", status, body)
        }
        if (status !in 200..299) {
            throw SuperGrokQuotaException("Grok Imagine request failed (HTTP $status). Try again later.", status, body)
        }
        return body
    }

    private fun requireToken(accessToken: String): String {
        return accessToken.trim().ifBlank {
            throw SuperGrokQuotaException("Grok login required. Log in from SuperGrok settings.")
        }
    }

    companion object {
        const val DEFAULT_IMAGE_MODEL = "grok-imagine-image"
        const val DEFAULT_VIDEO_MODEL = "grok-imagine-video"
        const val DEFAULT_VIDEO_DURATION_SECONDS = 8
        const val DEFAULT_VIDEO_POLL_TIMEOUT_SECONDS = 180
        private const val MIN_VIDEO_DURATION_SECONDS = 1
        private const val MAX_VIDEO_DURATION_SECONDS = 15
        private const val MAX_VIDEO_POLL_TIMEOUT_SECONDS = 600
        private const val VIDEO_POLL_INTERVAL_MS = 5_000L
        private const val IMAGES_GENERATIONS_PATH = "images/generations"
        private const val VIDEOS_GENERATIONS_PATH = "videos/generations"
        private const val USER_AGENT = "openai-usage-quota-intellij"
        private val DEFAULT_BASE_URI = URI.create("https://api.x.ai/v1/")

        fun createDefault(): SuperGrokImagineClient = SuperGrokImagineClient()

        fun extractRequestId(responseBody: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
                ?: return null
            return (root["request_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (root["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }

        fun extractVideoStatus(responseBody: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
                ?: return null
            return (root["status"] as? JsonPrimitive)?.contentOrNull
        }

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }
}

@Serializable
private data class GrokImageGenerationRequestDto(
    val model: String,
    val prompt: String,
    val n: Int,
    @SerialName("response_format") val responseFormat: String,
)

@Serializable
private data class GrokVideoGenerationRequestDto(
    val model: String,
    val prompt: String,
    val duration: Int? = null,
    val image: GrokVideoImageDto? = null,
)

@Serializable
private data class GrokVideoImageDto(
    val url: String,
)
