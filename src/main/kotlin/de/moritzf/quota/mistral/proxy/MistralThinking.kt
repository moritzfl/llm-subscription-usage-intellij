package de.moritzf.quota.mistral.proxy

import de.moritzf.proxy.server.JsonHelper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Mistral reasoning answers carry `content` as a list of `thinking` and `text` chunks, which OpenAI
 * chat clients cannot read. Thinking moves to `reasoning_content`, and a `reasoning_content` the
 * client sends back is replayed as a thinking chunk, as Mistral asks for in multi-turn reasoning.
 */
internal object MistralThinking {
    /**
     * Mistral documents `none`..`xhigh` for its reasoning chat models (the Vibe catalog narrows
     * some to none/high); GLM 5.3 accepts `low`, `high`, and `max` but not `none`. Deprecated
     * Magistral models always reason and keep getting no effort.
     */
    fun reasoningEfforts(modelId: String, supportsReasoning: Boolean?): List<String> {
        val id = modelId.lowercase()
        return when {
            supportsReasoning != true || id.startsWith("magistral-") -> emptyList()
            id.contains("glm") -> listOf("low", "high", "max")
            else -> listOf("none", "minimal", "low", "medium", "high", "xhigh")
        }
    }

    fun requestBody(body: JsonObject, supportsReasoning: Boolean): JsonObject {
        val messages = body["messages"] as? JsonArray ?: return body
        if (messages.none { (it as? JsonObject)?.containsKey("reasoning_content") == true }) {
            return body
        }
        val replayed = buildJsonArray {
            messages.forEach { message ->
                val item = message as? JsonObject
                if (item == null || "reasoning_content" !in item) {
                    add(message)
                    return@forEach
                }
                val thinking =
                    (item["reasoning_content"] as? JsonPrimitive)?.contentOrNull?.takeIf {
                        supportsReasoning && it.isNotEmpty()
                    }
                add(
                    buildJsonObject {
                        item.forEach { (key, value) ->
                            if (key == "reasoning_content") return@forEach
                            if (key == "content" && thinking != null) return@forEach
                            put(key, value)
                        }
                        if (thinking != null) {
                            put(
                                "content",
                                buildJsonArray {
                                    add(thinkChunk(thinking))
                                    textChunks(item["content"]).forEach(::add)
                                },
                            )
                        }
                    }
                )
            }
        }
        return JsonObject(body + ("messages" to replayed))
    }

    fun chatResponse(raw: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return raw
        val adapted = adaptChoices(root, "message") ?: return raw
        return JsonHelper.encodeToString(adapted)
    }

    fun chatStreamData(data: String): String {
        val root = JsonHelper.parseToJsonElementOrNull(data) as? JsonObject ?: return data
        val adapted = adaptChoices(root, "delta") ?: return data
        return JsonHelper.encodeToString(adapted)
    }

    private fun adaptChoices(root: JsonObject, messageKey: String): JsonObject? {
        val choices = root["choices"] as? JsonArray ?: return null
        // Only reasoning answers use thinking chunks; leave other chunk lists untouched.
        val withThinking = choices.any { element ->
            val content =
                ((element as? JsonObject)?.get(messageKey) as? JsonObject)?.get("content")
                    as? JsonArray
            content?.any {
                ((it as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull == "thinking"
            } == true
        }
        if (!withThinking) return null
        val adapted =
            JsonArray(
                choices.map { element ->
                    val choice = element as? JsonObject ?: return@map element
                    val message = choice[messageKey] as? JsonObject ?: return@map element
                    val content = message["content"] as? JsonArray ?: return@map element
                    JsonObject(choice + (messageKey to splitContent(message, content, messageKey)))
                }
            )
        return JsonObject(root + ("choices" to adapted))
    }

    private fun splitContent(
        message: JsonObject,
        content: JsonArray,
        messageKey: String,
    ): JsonObject {
        val text = StringBuilder()
        val thinking = StringBuilder()
        content.forEach { chunk ->
            val item = chunk as? JsonObject ?: return@forEach
            when ((item["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> text.append(stringValue(item["text"]))
                "thinking" -> thinking.append(thinkingText(item["thinking"]))
            }
        }
        return buildJsonObject {
            message.forEach { (key, value) ->
                if (key != "content" && key != "reasoning_content") put(key, value)
            }
            when {
                text.isNotEmpty() -> put("content", text.toString())
                // A final message keeps OpenAI's shape: null beside tool calls, else "".
                messageKey == "message" ->
                    put(
                        "content",
                        if ((message["tool_calls"] as? JsonArray)?.isNotEmpty() == true) JsonNull
                        else JsonPrimitive(""),
                    )
            }
            if (thinking.isNotEmpty()) put("reasoning_content", thinking.toString())
        }
    }

    private fun thinkingText(value: JsonElement?): String =
        when (value) {
            is JsonArray ->
                value.joinToString("") { part ->
                    val item = part as? JsonObject ?: return@joinToString stringValue(part)
                    stringValue(item["text"])
                }
            else -> stringValue(value)
        }

    private fun stringValue(value: JsonElement?): String =
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

    private fun thinkChunk(text: String): JsonObject = buildJsonObject {
        put("type", "thinking")
        put(
            "thinking",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    }
                )
            },
        )
    }

    private fun textChunks(content: JsonElement?): List<JsonElement> =
        when (content) {
            is JsonArray -> content.toList()
            is JsonPrimitive ->
                content.contentOrNull
                    ?.takeIf { content.isString && it.isNotEmpty() }
                    ?.let {
                        listOf(
                            buildJsonObject {
                                put("type", "text")
                                put("text", it)
                            }
                        )
                    }
                    .orEmpty()
            else -> emptyList()
        }
}
