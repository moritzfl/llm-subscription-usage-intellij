package de.moritzf.proxy.subscription

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.isTextual
import de.moritzf.proxy.server.remove
import de.moritzf.proxy.server.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Junie sends one chat shape to every provider. Several upstreams reject pieces of it,
 * or spend a small `max_tokens` budget on hidden reasoning and return an empty answer.
 */
object ChatUpstreamCompat {
    fun adaptChat(modelId: String, body: JsonObject): JsonObject {
        val omitted = omitUnsupportedChatFields(modelId, body)
        if (!modelId.trim().lowercase().startsWith("gemini-") || !hasTools(omitted)) return omitted
        return addTokenHeadroom(omitted, GEMINI_TOOL_REASONING_HEADROOM)
    }

    fun omitUnsupportedChatFields(modelId: String, body: JsonObject): JsonObject {
        val omit = unsupportedChatFields(modelId)
        if (omit.isEmpty() || body.keys.none { it in omit }) return body
        return buildJsonObject {
            body.forEach { (key, value) ->
                if (key !in omit) put(key, value)
            }
        }
    }

    fun unsupportedChatFields(modelId: String): Set<String> {
        val id = modelId.trim().lowercase()
        return buildSet {
            // Copilot chat rejects stop for these ids. Responses-bridged models already
            // emulate stop in ChatCompletionsHandler; this covers the chat path.
            if (id.startsWith("gpt-5-mini") || id.startsWith("kimi-k2.7")) add("stop")
            // kimi-k2.7-code rejects any reasoning_effort. Copilot Gemini accepts only
            // low/medium/high, counts that thinking against max_tokens, and then returns
            // empty content for Junie's small cap. "none" is also rejected, so omit it.
            if (id.startsWith("kimi-k2.7") || id.startsWith("gemini-")) add("reasoning_effort")
        }
    }

    fun applyStop(raw: String, requestBody: JsonObject): String {
        if (raw.isBlank()) return raw
        val stopSequences = stopSequences(requestBody)
        if (stopSequences.isEmpty()) return raw
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return raw
        val choices = root["choices"] as? JsonArray ?: return raw
        var changed = false
        val updatedChoices = choices.map { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@map choiceElement
            val message = choice["message"] as? JsonObject ?: return@map choiceElement
            val content = message["content"]
            if (!content.isTextual()) return@map choiceElement
            val cut = cutAtStopSequence(content.text, stopSequences) ?: return@map choiceElement
            changed = true
            val updatedMessage = buildJsonObject {
                message.forEach { (key, value) ->
                    put(key, if (key == "content") JsonPrimitive(cut.content) else value)
                }
            }
            buildJsonObject {
                choice.forEach { (key, value) ->
                    if (key != "message" && key != "finish_reason" && key != "finish_details") put(key, value)
                }
                put("message", updatedMessage)
                put("finish_reason", "stop")
                put("finish_details", buildJsonObject {
                    put("type", "stop")
                    put("stop", cut.sequence)
                })
            }
        }
        if (!changed) return raw
        return JsonHelper.encodeToString(buildJsonObject {
            root.forEach { (key, value) ->
                put(key, if (key == "choices") JsonArray(updatedChoices) else value)
            }
        })
    }

    private fun hasTools(body: JsonObject): Boolean {
        return listOf("tools", "functions").any { (body[it] as? JsonArray)?.isNotEmpty() == true }
    }

    private fun addTokenHeadroom(body: JsonObject, headroom: Int): JsonObject {
        if (body.keys.none { it == "max_tokens" || it == "max_completion_tokens" }) return body
        return buildJsonObject {
            body.forEach { (key, value) ->
                val amount = (value as? JsonPrimitive)?.intOrNull
                if ((key == "max_tokens" || key == "max_completion_tokens") && amount != null) {
                    put(key, amount + headroom)
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun stopSequences(body: JsonObject): List<String> {
        val stop = body["stop"] ?: return emptyList()
        if (stop.isTextual() && stop.text.isNotEmpty()) return listOf(stop.text)
        if (stop !is JsonArray) return emptyList()
        return stop.mapNotNull { sequence ->
            sequence.takeIf { it.isTextual() }?.text?.takeIf { it.isNotEmpty() }
        }
    }

    private fun cutAtStopSequence(text: String, stopSequences: List<String>): StopCut? {
        var earliestStart = -1
        var firedSequence: String? = null
        for (sequence in stopSequences) {
            val start = text.indexOf(sequence)
            if (start == -1) continue
            if (earliestStart == -1 || start < earliestStart) {
                earliestStart = start
                firedSequence = sequence
            }
        }
        return if (firedSequence != null) StopCut(text.substring(0, earliestStart), firedSequence) else null
    }

    private data class StopCut(val content: String, val sequence: String)

    private const val GEMINI_TOOL_REASONING_HEADROOM = 2_048
}
