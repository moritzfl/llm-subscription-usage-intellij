package de.moritzf.proxy.fim

import de.moritzf.proxy.server.booleanPath
import de.moritzf.proxy.server.intPath
import de.moritzf.proxy.server.stringPath
import de.moritzf.proxy.server.stringPathOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

data class CompletionsRequest(
    val model: String,
    val prompt: String,
    val suffix: String? = null,
    val stream: Boolean = false,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val stop: List<String> = emptyList(),
    val echo: Boolean = false,
) {
    companion object {
        fun parse(body: JsonObject): CompletionsRequest {
            val maxTokens = body.intPath("max_tokens").takeIf { it > 0 }
                ?: body.intPath("max_completion_tokens").takeIf { it > 0 }
            return CompletionsRequest(
                model = body.stringPath("model").trim(),
                prompt = promptText(body),
                suffix = body.stringPathOrNull("suffix")?.takeIf { it.isNotBlank() },
                stream = body.booleanPath("stream"),
                maxTokens = maxTokens,
                temperature = (body["temperature"] as? JsonPrimitive)?.doubleOrNull,
                stop = stopSequences(body),
                echo = body.booleanPath("echo"),
            )
        }

        private fun promptText(body: JsonObject): String {
            val prompt = body["prompt"] ?: return ""
            if (prompt is JsonPrimitive) return prompt.content
            if (prompt is JsonArray) {
                return prompt.joinToString("") { element ->
                    (element as? JsonPrimitive)?.contentOrNull.orEmpty()
                }
            }
            return ""
        }

        internal fun stopSequences(body: JsonObject): List<String> {
            val stop = body["stop"] ?: return emptyList()
            if (stop is JsonPrimitive) {
                return stop.contentOrNull?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
            }
            if (stop is JsonArray) {
                return stop.mapNotNull { element ->
                    (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                }
            }
            return emptyList()
        }
    }
}
