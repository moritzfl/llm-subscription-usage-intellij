package de.moritzf.proxy

import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.config.HostBinding
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.isArray
import de.moritzf.proxy.server.isTextual
import de.moritzf.proxy.sse.SseParser
import de.moritzf.proxy.util.Json
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class StartupProbeResult(
    val success: Boolean,
    val statusCode: Int,
    val message: String,
    val responseText: String?,
    val model: String,
)

internal object StartupProbe {
    fun verify(
        config: ServerConfig,
        availableModels: List<String>,
        apiKey: String?,
        httpClient: HttpClient,
    ): StartupProbeResult {
        val model = selectModel(config, availableModels)
        val body = """{"model":"$model","messages":[{"role":"user","content":"Hello!"}],"stream":true}"""
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(startupProbeUrl(config)))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (!apiKey.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        return try {
            val response = httpClient.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val status = response.statusCode()
            val responseText = if (status in 200..<300) {
                extractResponseText(response.body())
            } else {
                formatRawBody(response.body())
            }
            if (status in 200..<300) {
                val hasModelResponse = hasActualResponse(responseText)
                return StartupProbeResult(
                    hasModelResponse,
                    status,
                    if (hasModelResponse) "HTTP $status" else "HTTP $status, no model response text",
                    responseText,
                    model,
                )
            }
            StartupProbeResult(false, status, "HTTP $status", responseText, model)
        } catch (exception: Exception) {
            StartupProbeResult(false, 0, "${exception.javaClass.simpleName}: ${exception.message}", null, model)
        }
    }

    fun firstConfiguredApiKey(config: ServerConfig): String? {
        if (config.adminKey != null) {
            return config.adminKey
        }
        return config.apiKeys.keys.firstOrNull()
    }

    private fun extractResponseText(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) {
            return "<empty response body>"
        }
        if (looksLikeSse(responseBody)) {
            return extractStreamingResponseText(responseBody)
        }
        return try {
            val root = Json.INSTANCE.parseToJsonElement(responseBody) as? JsonObject
                ?: return "<missing choices[0].message.content>"
            val choices = root["choices"]
            if (choices !is JsonArray || choices.isEmpty()) {
                return "<missing choices[0].message.content>"
            }
            val firstChoice = choices[0] as? JsonObject
            val message = firstChoice?.get("message") as? JsonObject
            val content = message?.get("content") ?: return "<missing choices[0].message.content>"
            if (content is JsonNull) {
                return "<null choices[0].message.content>"
            }
            if (content is JsonPrimitive && content.isString) {
                return formatText(content.content)
            }
            formatText(JsonHelper.encodeToString(content))
        } catch (_: Exception) {
            "<unparseable response body: ${formatText(responseBody)}>"
        }
    }

    private fun extractStreamingResponseText(responseBody: String): String {
        val text = StringBuilder()
        var sawNullContent = false
        try {
            val input = ByteArrayInputStream(responseBody.toByteArray(StandardCharsets.UTF_8))
            for (event in SseParser.parse(input)) {
                val data = event.data()
                if (data.isNullOrBlank()) {
                    continue
                }
                if (data == "[DONE]") {
                    break
                }
                val root = Json.INSTANCE.parseToJsonElement(data) as? JsonObject ?: continue
                val choices = root["choices"]
                if (choices !is JsonArray) {
                    continue
                }
                for (choice in choices) {
                    val delta = (choice as? JsonObject)?.get("delta") as? JsonObject ?: continue
                    val content = delta["content"] ?: continue
                    if (content is JsonNull) {
                        sawNullContent = true
                    } else if (content is JsonPrimitive && content.isString) {
                        text.append(content.content)
                    } else {
                        text.append(JsonHelper.encodeToString(content))
                    }
                }
            }
        } catch (_: Exception) {
            return "<unparseable streaming response body: ${formatText(responseBody)}>"
        }
        if (text.isNotEmpty()) {
            return formatText(text.toString())
        }
        return if (sawNullContent) {
            "<null streaming choices[].delta.content>"
        } else {
            "<missing streaming choices[].delta.content>"
        }
    }

    private fun looksLikeSse(responseBody: String): Boolean {
        val trimmed = responseBody.trimStart()
        return trimmed.startsWith("data:") || trimmed.startsWith("event:")
    }

    private fun hasActualResponse(responseText: String?): Boolean {
        return !responseText.isNullOrBlank() && !responseText.startsWith("<")
    }

    private fun formatRawBody(responseBody: String?): String {
        if (responseBody.isNullOrBlank()) {
            return "<empty response body>"
        }
        return formatText(responseBody)
    }

    private fun formatText(text: String): String {
        return text.replace("\r", "\\r").replace("\n", "\\n")
    }

    private fun selectModel(config: ServerConfig, availableModels: List<String>): String {
        if (availableModels.isNotEmpty()) {
            return availableModels.first()
        }
        val configuredModels = config.models
        if (!configuredModels.isNullOrEmpty()) {
            return configuredModels.first()
        }
        return ServerConfig.DEFAULT_MODEL
    }

    @Suppress("HttpUrlsUsage")
    private fun startupProbeUrl(config: ServerConfig): String {
        val host = HostBinding.clientHostForBindHost(config.host)
        return "http://${HostBinding.hostForUri(host)}:${config.port}/v1/chat/completions"
    }
}