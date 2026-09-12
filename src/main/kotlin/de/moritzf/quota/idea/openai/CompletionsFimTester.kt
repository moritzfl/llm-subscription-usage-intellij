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

data class CompletionsFimTestResult(
    val ok: Boolean,
    val status: String,
    val elapsedMs: Long? = null,
    val sample: String? = null,
    val insert: String? = null,
    val assembled: String? = null,
    val detail: String? = null,
)

object CompletionsFimTester {
    const val SAMPLE_PROMPT = "fun add(a: Int, b: Int): Int {\n    return "
    const val SAMPLE_SUFFIX = "\n}\n"
    const val SAMPLE_WITH_CURSOR = "fun add(a: Int, b: Int): Int {\n    return |\n}\n"

    fun test(
        baseUrl: String,
        apiKey: String,
        modelId: String = CompletionsConfig.FIM_ALIAS_ID,
        timeout: Duration = Duration.ofSeconds(15),
        httpClient: HttpClient = CLIENT,
    ): CompletionsFimTestResult {
        val url = baseUrl.trimEnd('/') + "/v1/completions"
        val body =
            """{"model":${jsonString(modelId)},"prompt":${jsonString(SAMPLE_PROMPT)},"suffix":${jsonString(SAMPLE_SUFFIX)},"stream":false,"max_tokens":48}"""
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        val started = System.nanoTime()
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (exception: Exception) {
            return CompletionsFimTestResult(
                ok = false,
                status = "Test failed",
                detail = "Request failed: ${exception.message ?: exception::class.java.simpleName}",
            )
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        val raw = response.body()
        if (response.statusCode() !in 200..<300) {
            return CompletionsFimTestResult(
                ok = false,
                status = "HTTP ${response.statusCode()}",
                elapsedMs = elapsedMs,
                detail = raw.take(2000).ifBlank { "Empty error body" },
            )
        }
        return formatSuccess(completionText(raw), elapsedMs, raw)
    }

    internal fun formatSuccess(insert: String?, elapsedMs: Long, raw: String = ""): CompletionsFimTestResult {
        if (insert.isNullOrEmpty()) {
            return CompletionsFimTestResult(
                ok = false,
                status = "Empty insert",
                elapsedMs = elapsedMs,
                sample = SAMPLE_WITH_CURSOR.trimEnd(),
                detail = buildString {
                    append("The adapter ran, but the model returned no insert text.")
                    if (raw.isNotBlank()) {
                        append("\n\n")
                        append(raw.take(2000))
                    }
                },
            )
        }
        return CompletionsFimTestResult(
            ok = true,
            status = "Fill-in looks usable",
            elapsedMs = elapsedMs,
            sample = SAMPLE_WITH_CURSOR.trimEnd(),
            insert = insert,
            assembled = (SAMPLE_PROMPT + insert + SAMPLE_SUFFIX).trimEnd(),
        )
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
