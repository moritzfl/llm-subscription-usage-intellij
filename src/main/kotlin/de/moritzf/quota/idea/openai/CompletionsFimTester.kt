package de.moritzf.quota.idea.openai

import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.server.JsonHelper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object CompletionsFimTester {
    const val SAMPLE_PROMPT = "fun add(a: Int, b: Int): Int {\n    return "
    const val SAMPLE_SUFFIX = "\n}\n"

    fun test(
        baseUrl: String,
        apiKey: String,
        modelId: String = CompletionsConfig.FIM_ALIAS_ID,
        timeout: Duration = Duration.ofSeconds(15),
        httpClient: HttpClient = CLIENT,
    ): String {
        val url = baseUrl.trimEnd('/') + "/v1/completions"
        val body =
            """{"model":${jsonString(modelId)},"prompt":${jsonString(SAMPLE_PROMPT)},"suffix":${jsonString(SAMPLE_SUFFIX)},"stream":false,"max_tokens":48}"""
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: Exception) {
            return "Request failed: ${exception.message ?: exception::class.java.simpleName}"
        }
        val raw = response.body()
        if (response.statusCode() !in 200..<300) {
            return "HTTP ${response.statusCode()}: ${raw.take(2000)}"
        }
        val text = completionText(raw)
        return if (text.isNullOrEmpty()) {
            "Empty completion.\n\nRaw:\n${raw.take(2000)}"
        } else {
            "Insert text:\n$text"
        }
    }

    internal fun completionText(raw: String): String? {
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val choice = choices.firstOrNull() as? JsonObject ?: return null
        val text = choice["text"] as? JsonPrimitive
        return text?.contentOrNull
    }

    private fun jsonString(value: String): String {
        return JsonHelper.encodeToString(JsonPrimitive(value))
    }

    private val CLIENT: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .build()
}
