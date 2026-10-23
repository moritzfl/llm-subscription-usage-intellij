package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.model.ReasoningEffort
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Maps an OpenAI `reasoning_effort` onto the Anthropic Messages controls OpenCode's gateway
 * accepts. Live probes on OpenCode Go (2026-10-23):
 * - Claude Haiku 5.5 takes `output_config.effort`; `thinking.enabled` with a budget is rejected
 *   with "use thinking.type.adaptive and output_config.effort".
 * - Qwen3.8 Flash thinks by default and honors `thinking.type: disabled`.
 * - MiniMax M3 ignores effort alone; `thinking.type: adaptive` turns thinking on.
 */
internal object AnthropicReasoning {
    private val LEVELS = listOf("low", "medium", "high", "xhigh", "max")

    fun apply(body: JsonObject, modelId: String): JsonObject {
        val effort = (body["reasoning_effort"] as? JsonPrimitive)?.contentOrNull ?: return body
        val family = family(modelId)
        val disabled = effort.trim().lowercase() in setOf("none", "minimal")
        if (family == Family.CLAUDE) {
            val levels = claudeEffortLevels(modelId) ?: return JsonObject(body - "reasoning_effort")
            if (disabled) {
                // Anthropic cannot turn thinking off on every model; `low` is the floor.
                return withEffort(body, "low")
            }
            return ReasoningEffort.nearest(effort, levels)?.let { withEffort(body, it) } ?: body
        }
        if (disabled) {
            return when (family) {
                Family.CLAUDE,
                Family.OTHER -> withEffort(body, "low")
                Family.ALWAYS_THINKING,
                Family.ADAPTIVE_ONLY ->
                    JsonObject(body - "reasoning_effort" + ("thinking" to disabledThinking()))
            }
        }
        val level = ReasoningEffort.nearest(effort, LEVELS) ?: return body
        val withEffort = withEffort(body, level)
        // Qwen and MiniMax accept output_config.effort through the gateway; only their
        // thinking switches differ.
        return if (family == Family.ADAPTIVE_ONLY) {
            JsonObject(withEffort + ("thinking" to adaptiveThinking()))
        } else {
            withEffort
        }
    }

    private fun withEffort(body: JsonObject, level: String): JsonObject {
        val configured = body["output_config"] as? JsonObject ?: JsonObject(emptyMap())
        val outputConfig = buildJsonObject {
            configured.forEach { (key, value) -> if (key != "effort") put(key, value) }
            put("effort", JsonPrimitive(level))
        }
        return JsonObject(body - "reasoning_effort" + ("output_config" to outputConfig))
    }

    private enum class Family {
        /** Effort controls thinking; a `thinking` field is unnecessary and can conflict. */
        CLAUDE,
        /** Thinks unless told otherwise. */
        ALWAYS_THINKING,
        /** Needs `thinking.type: adaptive` before effort has any effect. */
        ADAPTIVE_ONLY,
        /** Accepts effort as is. */
        OTHER,
    }

    private fun family(modelId: String): Family {
        val id = modelId.lowercase()
        return when {
            id.startsWith("claude-") -> Family.CLAUDE
            id.startsWith("minimax") -> Family.ADAPTIVE_ONLY
            id.startsWith("qwen") -> Family.ALWAYS_THINKING
            else -> Family.OTHER
        }
    }

    /**
     * Effort arrived with Claude 4.6. Sonnet 4.6 stops at `max`; Opus 4.7+, Sonnet 5, and Haiku 5
     * also take `xhigh`. Older models keep no effort (thinking, if any, is client-controlled).
     */
    private fun claudeEffortLevels(modelId: String): List<String>? {
        val match = CLAUDE_VERSION.find(modelId.lowercase()) ?: return null
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toIntOrNull() ?: 0
        return when {
            major >= 5 || (major == 4 && minor >= 7) -> LEVELS
            major == 4 && minor == 6 -> listOf("low", "medium", "high", "max")
            else -> null
        }
    }

    private fun adaptiveThinking(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("adaptive"))
    }

    private fun disabledThinking(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("disabled"))
    }

    private val CLAUDE_VERSION = Regex("^claude-[a-z]+-(\\d+)(?:\\.(\\d+))?")
}
