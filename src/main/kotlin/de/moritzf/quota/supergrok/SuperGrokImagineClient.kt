package de.moritzf.quota.supergrok

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
        targetFile: String? = null,
        baseDirectory: Path? = null,
    ): String {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            throw SuperGrokQuotaException("Image prompt is required.")
        }
        val token = requireToken(accessToken)
        val trimmedModel = model.trim().ifBlank { DEFAULT_IMAGE_MODEL }
        val outputTarget = resolveOptionalImageTarget(targetFile, baseDirectory)
        if (outputTarget?.error != null) {
            throw SuperGrokQuotaException(outputTarget.error)
        }
        // Prefer URL when returning to the agent. Use b64 only when writing a local file
        // so agents do not have to ingest large base64 payloads.
        val responseFormat = if (outputTarget?.path != null) "b64_json" else "url"
        val body = JsonSupport.json.encodeToString(
            GrokImageGenerationRequestDto(
                model = trimmedModel,
                prompt = trimmedPrompt,
                n = 1,
                responseFormat = responseFormat,
            ),
        )
        val responseBody = postJson(token, IMAGES_GENERATIONS_PATH, body, requestTimeoutSeconds = 120)
        if (outputTarget?.path == null) {
            return McpJson.providerJsonOrRaw(responseBody)
        }
        return writeFirstImageToFile(responseBody, outputTarget.path, outputTarget.format!!)
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

    private fun writeFirstImageToFile(responseBody: String, targetFile: Path, format: String): String {
        val b64 = extractFirstB64Image(responseBody)
            ?: throw SuperGrokQuotaException(
                "Grok image generation returned no b64_json image data.",
                200,
                responseBody,
            )
        val bytes = writeImageFile(b64, targetFile, format)
            ?: throw SuperGrokQuotaException("Could not write generated image as $format.")
        return buildJsonObject {
            put("output_file", targetFile.toString())
            put("format", format)
            put("bytes", bytes)
        }.toString()
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

    private fun resolveOptionalImageTarget(targetFile: String?, baseDirectory: Path?): ImageOutputTarget? {
        val trimmed = targetFile?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return resolveImageOutputTarget(trimmed, baseDirectory)
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
        private val SUPPORTED_IMAGE_FORMATS: Set<String> = ImageIO.getWriterFormatNames()
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        fun createDefault(): SuperGrokImagineClient = SuperGrokImagineClient()

        fun resolveImageOutputTarget(targetFile: String, baseDirectory: Path?): ImageOutputTarget {
            val path = try {
                Path.of(targetFile.trim())
            } catch (exception: InvalidPathException) {
                return ImageOutputTarget(error = exception.message ?: "Invalid image target file path.")
            }
            if (path.isAbsolute) {
                return ImageOutputTarget(error = "Image target file must be relative to the project directory.")
            }
            val base = (baseDirectory ?: Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize()
            val resolved = base.resolve(path).normalize()
            if (!resolved.startsWith(base)) {
                return ImageOutputTarget(error = "Image target file must stay inside the project directory.")
            }
            val format = resolved.fileName?.toString()
                ?.substringAfterLast('.', missingDelimiterValue = "")
                ?.lowercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: return ImageOutputTarget(error = "Image target file must include an extension.")
            if (format !in SUPPORTED_IMAGE_FORMATS) {
                return ImageOutputTarget(
                    error = "Unsupported image format '$format'. Supported formats: ${SUPPORTED_IMAGE_FORMATS.sorted().joinToString(", ")}.",
                )
            }
            return ImageOutputTarget(path = resolved, format = format)
        }

        fun extractFirstB64Image(responseBody: String): String? {
            val root = runCatching { JsonSupport.json.parseToJsonElement(responseBody) as? JsonObject }.getOrNull()
                ?: return null
            val data = root["data"] as? JsonArray ?: return null
            for (item in data) {
                val obj = item as? JsonObject ?: continue
                val b64 = (obj["b64_json"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                if (b64 != null) return b64
            }
            return null
        }

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

        fun writeImageFile(b64Json: String, targetFile: Path, format: String): Long? {
            val imageBytes = runCatching { Base64.getDecoder().decode(b64Json) }.getOrNull() ?: return null
            val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return null
            val parent = targetFile.parent
            if (parent != null) {
                Files.createDirectories(parent)
            }
            if (!ImageIO.write(image, format, targetFile.toFile())) {
                return null
            }
            return Files.size(targetFile)
        }

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }
    }

    data class ImageOutputTarget(
        val path: Path? = null,
        val format: String? = null,
        val error: String? = null,
    )
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
