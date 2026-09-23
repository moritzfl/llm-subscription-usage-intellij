package de.moritzf.proxy.model

import de.moritzf.proxy.util.Json
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal class CodexReserveHop(
    private val aliasResolver: ModelAliasResolver = ModelAliasResolver(),
) {
    fun isEligibleRequest(path: String, method: String?, body: String?): Boolean {
        if (body.isNullOrBlank() || !method.equals("POST", ignoreCase = true)) {
            return false
        }
        val normalized = path.substringBefore('?').trimEnd('/')
        if (normalized != "/responses" && !normalized.endsWith("/responses")) {
            return false
        }
        return originalModel(body)?.let { isLunaModel(it) } == true
    }

    fun isUsageLimit(errorBody: String): Boolean {
        val lowered = errorBody.lowercase(Locale.ROOT)
        return "usage_limit_reached" in lowered ||
            "usage_not_included" in lowered ||
            "usage limit" in lowered
    }

    fun originalModel(body: String): String? {
        val root = parseObject(body) ?: return null
        val model = (root["model"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return aliasResolver.resolve(model).model ?: model
    }

    fun rewriteRequestToReserve(body: String): String? {
        val root = parseObject(body) ?: return null
        val original = originalModel(body) ?: return null
        if (!isLunaModel(original)) {
            return null
        }
        return JSON.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                root.forEach { (key, value) ->
                    when {
                        key == "model" -> put("model", JsonPrimitive(RESERVE_MODEL))
                        key == "reasoning" && value is JsonObject -> put("reasoning", rewriteReasoning(value))
                        else -> put(key, value)
                    }
                }
            },
        )
    }

    fun rewritingStream(input: InputStream, originalModel: String): InputStream {
        if (originalModel.isBlank() || isReserveModel(originalModel)) {
            return input
        }
        return NeedleReplaceInputStream(
            input,
            "\"$RESERVE_MODEL\"",
            "\"$originalModel\"",
        )
    }

    private fun rewriteReasoning(reasoning: JsonObject): JsonObject {
        val effort = (reasoning["effort"] as? JsonPrimitive)?.content
        val clamped = aliasResolver.clampReasoningEffort(RESERVE_MODEL, effort) ?: effort
        return buildJsonObject {
            reasoning.forEach { (key, value) ->
                if (key == "effort" && clamped != null) {
                    put("effort", JsonPrimitive(clamped))
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun parseObject(body: String): JsonObject? {
        return runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    companion object {
        const val RESERVE_MODEL: String = "gpt-reserve"
        private val JSON = Json.INSTANCE

        fun isReserveModel(model: String): Boolean {
            val name = model.trim().lowercase(Locale.ROOT)
            return name == RESERVE_MODEL || name.startsWith("$RESERVE_MODEL-")
        }

        fun isLunaModel(model: String): Boolean {
            // Codex LUNA_MODEL is gpt-6-luna. Keep gpt-5.6-luna so the previous fast model still hops.
            // gpt-reserve is not eligible (would hop to itself). Sol/Astra/Terra stay on their own buckets.
            val name = model.trim().lowercase(Locale.ROOT)
            return name.startsWith("gpt-6-luna") || name.startsWith("gpt-5.6-luna")
        }
    }
}

internal class NeedleReplaceInputStream(
    delegate: InputStream,
    needle: String,
    replacement: String,
) : FilterInputStream(delegate) {
    private val needleBytes = needle.toByteArray(StandardCharsets.UTF_8)
    private val replacementBytes = replacement.toByteArray(StandardCharsets.UTF_8)
    private val pending = ArrayDeque<Int>()
    private var eof = false

    override fun read(): Int {
        while (pending.isEmpty() && !eof) {
            fill()
        }
        if (pending.isEmpty()) {
            return -1
        }
        return pending.removeFirst()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) {
            return 0
        }
        var count = 0
        while (count < length) {
            val next = read()
            if (next == -1) {
                return if (count == 0) -1 else count
            }
            buffer[offset + count] = next.toByte()
            count++
        }
        return count
    }

    private fun fill() {
        val first = super.read()
        if (first == -1) {
            eof = true
            return
        }
        if (first != needleByte(0)) {
            pending.addLast(first)
            return
        }
        val matched = IntArray(needleBytes.size)
        matched[0] = first
        var i = 1
        while (i < needleBytes.size) {
            val next = super.read()
            if (next == -1) {
                eof = true
                matched.take(i).forEach { pending.addLast(it) }
                return
            }
            matched[i] = next
            if (next != needleByte(i)) {
                matched.take(i + 1).forEach { pending.addLast(it) }
                return
            }
            i++
        }
        replacementBytes.forEach { pending.addLast(it.toInt() and 0xFF) }
    }

    private fun needleByte(index: Int): Int = needleBytes[index].toInt() and 0xFF
}
