package de.moritzf.quota.idea.mcp

import com.aiproxyoauth.auth.AuthRequiredException
import com.aiproxyoauth.config.ServerConfig
import com.aiproxyoauth.model.CodexClientVersionResolver
import com.aiproxyoauth.server.UpstreamErrorMapper
import com.aiproxyoauth.transport.CodexHttpClient
import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.openai.proxy.QuotaCodexCredentialsProvider
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import javax.imageio.ImageIO
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

class CodexMcpClient(
    accessTokenProvider: () -> String?,
    accountIdProvider: () -> String?,
    tokenRefresher: (staleAccessToken: String?) -> String? = { null },
    codexVersionProvider: () -> String = { CodexClientVersionResolver.resolve(null) },
    httpClient: HttpClient = defaultHttpClient(),
    upstreamBaseUri: URI = OpenAiProxyServer.DEFAULT_UPSTREAM_BASE_URI,
) {
    private val upstreamErrorMapper = UpstreamErrorMapper()
    private val client = CodexHttpClient(
        serverConfig(upstreamBaseUri),
        httpClient,
        QuotaCodexCredentialsProvider(accessTokenProvider, accountIdProvider, tokenRefresher, codexVersionProvider),
    )

    fun webSearch(query: String): CodexMcpResponse {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return CodexMcpResponse(errorJson("Search query is required."), true)
        }
        return postResponses(searchRequest(trimmedQuery), ::parseSearchResponse)
    }

    fun imageGeneration(
        prompt: String,
        targetFile: String? = null,
        baseDirectory: Path? = null,
    ): CodexMcpResponse {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            return CodexMcpResponse(errorJson("Image prompt is required."), true)
        }
        val trimmedTarget = targetFile?.trim()
        if (trimmedTarget.isNullOrBlank()) {
            return postResponses(imageGenerationRequest(trimmedPrompt), ::parseImageGenerationResponse)
        }
        val outputTarget = resolveImageOutputTarget(trimmedTarget, baseDirectory)
            ?: return CodexMcpResponse(errorJson("Image target file is required."), true)
        outputTarget.error?.let { return CodexMcpResponse(errorJson(it), true) }

        return postResponses(imageGenerationRequest(trimmedPrompt)) { body ->
            parseImageGenerationToFileResponse(body, outputTarget.path!!, outputTarget.format!!)
        }
    }

    private fun postResponses(
        request: JsonObject,
        parser: (String) -> CodexMcpResponse,
    ): CodexMcpResponse {
        return try {
            val response = client.requestString(
                RESPONSES_PATH,
                "POST",
                request.toString(),
                mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "text/event-stream",
                ),
            )
            if (response.statusCode() in 200..299) {
                parser(response.body())
            } else {
                val mapped = upstreamErrorMapper.map(response.statusCode(), response.body())
                CodexMcpResponse(mapped.body(), true)
            }
        } catch (exception: AuthRequiredException) {
            CodexMcpResponse(errorJson(exception.message ?: "OpenAI login required."), true)
        } catch (exception: Exception) {
            val message = exception.message?.takeIf { it.isNotBlank() } ?: exception::class.java.simpleName
            CodexMcpResponse(errorJson(message), true)
        }
    }

    private fun searchRequest(query: String): JsonObject {
        return buildJsonObject {
            put("model", RESPONSES_MODEL)
            put("instructions", SEARCH_INSTRUCTIONS)
            putJsonArray("input") {
                add(messageInput("Search the web for: $query"))
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "web_search")
                    put("external_web_access", true)
                    put("search_context_size", "medium")
                    putJsonArray("search_content_types") { add("text") }
                })
            }
            put("store", false)
            put("stream", true)
        }
    }

    private fun imageGenerationRequest(prompt: String): JsonObject {
        return buildJsonObject {
            put("model", RESPONSES_MODEL)
            put("instructions", IMAGE_GENERATION_INSTRUCTIONS)
            putJsonArray("input") {
                add(messageInput(prompt))
            }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("type", "image_generation")
                    put("output_format", "png")
                })
            }
            put("store", false)
            put("stream", true)
        }
    }

    private fun messageInput(text: String): JsonObject {
        return buildJsonObject {
            put("type", "message")
            put("role", "user")
            putJsonArray("content") {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            }
        }
    }

    private fun parseSearchResponse(body: String): CodexMcpResponse {
        val output = StringBuilder()
        var outputTextDone: String? = null
        var responseId: String? = null
        var webSearchUsage: JsonObject? = null
        var toolUsage: JsonObject? = null

        for (event in responsesDataEvents(body)) {
            failedMessage(event)?.let { return CodexMcpResponse(errorJson(it), true) }
            when (event.string("type")) {
                "response.output_text.delta" -> output.append(event.string("delta").orEmpty())
                "response.output_text.done" -> outputTextDone = event.string("text")
                "response.completed" -> {
                    val response = event.obj("response")
                    responseId = response?.string("id") ?: responseId
                    webSearchUsage = response?.obj("web_search") ?: webSearchUsage
                    toolUsage = response?.obj("tool_usage") ?: toolUsage
                }
            }
        }

        val text = outputTextDone?.takeIf { it.isNotBlank() } ?: output.toString()
        if (text.isBlank()) {
            return CodexMcpResponse(errorJson("Codex web search returned no output."), true)
        }

        return CodexMcpResponse(buildJsonObject {
            put("output", text)
            responseId?.let { put("response_id", it) }
            webSearchUsage?.let { put("web_search", it) }
            toolUsage?.let { put("tool_usage", it) }
        }.toString(), false)
    }

    private fun parseImageGenerationResponse(body: String): CodexMcpResponse {
        firstFailedMessage(body)?.let { return CodexMcpResponse(errorJson(it), true) }
        val imageResult = imageGenerationResult(body)
            ?: return CodexMcpResponse(errorJson("Codex image generation returned no image data."), true)

        return CodexMcpResponse(buildJsonObject {
            imageResult.responseId?.let { put("response_id", it) }
            putJsonArray("data") {
                add(buildJsonObject {
                    put("b64_json", imageResult.b64Json)
                    imageResult.revisedPrompt?.let { put("revised_prompt", it) }
                })
            }
            imageResult.toolUsage?.let { put("tool_usage", it) }
        }.toString(), false)
    }

    private fun parseImageGenerationToFileResponse(
        body: String,
        targetFile: Path,
        format: String,
    ): CodexMcpResponse {
        firstFailedMessage(body)?.let { return CodexMcpResponse(errorJson(it), true) }
        val imageResult = imageGenerationResult(body)
            ?: return CodexMcpResponse(errorJson("Codex image generation returned no image data."), true)

        val writtenBytes = writeImageFile(imageResult.b64Json, targetFile, format)
            ?: return CodexMcpResponse(errorJson("Could not write generated image as $format."), true)

        return CodexMcpResponse(buildJsonObject {
            imageResult.responseId?.let { put("response_id", it) }
            put("output_file", targetFile.toString())
            put("format", format)
            put("bytes", writtenBytes)
            imageResult.revisedPrompt?.let { put("revised_prompt", it) }
            imageResult.toolUsage?.let { put("tool_usage", it) }
        }.toString(), false)
    }

    private fun imageGenerationResult(body: String): ImageGenerationResult? {
        var responseId: String? = null
        var revisedPrompt: String? = null
        var b64Json: String? = null
        var toolUsage: JsonObject? = null

        for (event in responsesDataEvents(body)) {
            when (event.string("type")) {
                "response.output_item.done" -> {
                    val item = event.obj("item")
                    if (item?.string("type") == "image_generation_call") {
                        revisedPrompt = item.string("revised_prompt") ?: revisedPrompt
                        b64Json = item.string("result") ?: b64Json
                    }
                }
                "response.completed" -> {
                    val response = event.obj("response")
                    responseId = response?.string("id") ?: responseId
                    toolUsage = response?.obj("tool_usage") ?: toolUsage
                }
            }
        }

        val image = b64Json?.takeIf { it.isNotBlank() }
            ?: return null

        return ImageGenerationResult(responseId, revisedPrompt, image, toolUsage)
    }

    private fun firstFailedMessage(body: String): String? {
        return responsesDataEvents(body).firstNotNullOfOrNull(::failedMessage)
    }

    private fun resolveImageOutputTarget(targetFile: String, baseDirectory: Path?): ImageOutputTarget? {
        val trimmedTarget = targetFile.trim()
        if (trimmedTarget.isBlank()) {
            return null
        }
        val path = try {
            Path.of(trimmedTarget)
        } catch (exception: InvalidPathException) {
            return ImageOutputTarget(error = exception.message ?: "Invalid image target file path.")
        }
        val resolved = if (path.isAbsolute) {
            path.normalize()
        } else {
            val base = (baseDirectory ?: Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize()
            base.resolve(path).normalize()
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

    private fun writeImageFile(b64Json: String, targetFile: Path, format: String): Long? {
        val imageBytes = runCatching { Base64.getDecoder().decode(b64Json) }.getOrNull() ?: return null
        val image = ImageIO.read(ByteArrayInputStream(imageBytes)) ?: return null
        val parent = targetFile.parent
        if (parent != null) {
            Files.createDirectories(parent)
        }
        val written = ImageIO.write(image, format, targetFile.toFile())
        if (!written) {
            return null
        }
        return Files.size(targetFile)
    }

    private fun responsesDataEvents(body: String): Sequence<JsonObject> {
        return body.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() && it != "[DONE]" }
            .mapNotNull { line ->
                runCatching { JsonSupport.json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            }
    }

    private fun failedMessage(event: JsonObject): String? {
        if (event.string("type") != "response.failed") {
            return null
        }
        val error = event.obj("response")?.obj("error") ?: event.obj("error")
        return error?.string("message")
            ?: error?.string("code")
            ?: "Codex response failed."
    }

    private fun JsonObject.string(name: String): String? {
        return (this[name] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.obj(name: String): JsonObject? {
        return this[name].asObjectOrNull()
    }

    private fun JsonElement?.asObjectOrNull(): JsonObject? {
        return this as? JsonObject
    }

    data class CodexMcpResponse(val body: String, val isError: Boolean)

    private data class ImageGenerationResult(
        val responseId: String?,
        val revisedPrompt: String?,
        val b64Json: String,
        val toolUsage: JsonObject?,
    )

    private data class ImageOutputTarget(
        val path: Path? = null,
        val format: String? = null,
        val error: String? = null,
    )

    companion object {
        private const val RESPONSES_PATH = "/responses"
        private const val RESPONSES_MODEL = "gpt-5.5"
        private const val SEARCH_INSTRUCTIONS = "You are a concise assistant. Use web search when needed."
        private const val IMAGE_GENERATION_INSTRUCTIONS =
            "Use the image_generation tool to satisfy image requests. Return no extra commentary."
        private val SUPPORTED_IMAGE_FORMATS = ImageIO.getWriterFormatNames()
            .map { it.lowercase(Locale.ROOT) }
            .toSet()

        fun createDefault(): CodexMcpClient {
            return CodexMcpClient(
                accessTokenProvider = { QuotaAuthService.getInstance().getAccessTokenBlocking(QuotaProviderType.OPEN_AI) },
                accountIdProvider = { QuotaAuthService.getInstance().getAccountId(QuotaProviderType.OPEN_AI) },
                tokenRefresher = { staleToken ->
                    QuotaAuthService.getInstance().forceRefreshBlocking(QuotaProviderType.OPEN_AI, staleToken)
                },
            )
        }

        private fun defaultHttpClient(): HttpClient {
            return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build()
        }

        private fun serverConfig(upstreamBaseUri: URI): ServerConfig {
            return ServerConfig(
                "127.0.0.1",
                1,
                listOf(RESPONSES_MODEL),
                null,
                upstreamBaseUri.toString(),
                ServerConfig.DEFAULT_CLIENT_ID,
                null,
                null,
                "",
                false,
                emptyMap(),
                null,
                true,
                emptyList(),
                false,
                null,
                false,
                ServerConfig.DEFAULT_CODEX_INSTRUCTIONS_MODE,
                null,
                false,
                false,
            )
        }

        private fun errorJson(message: String): String {
            return buildJsonObject { put("error", message) }.toString()
        }
    }
}
