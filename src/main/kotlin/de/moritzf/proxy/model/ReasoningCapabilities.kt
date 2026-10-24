package de.moritzf.proxy.model

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Reads and writes the two model-list conventions for reasoning levels: LiteLLM's `model_info`
 * (`reasoning_effort_levels`, `supports_*_reasoning_effort`, `supports_reasoning`) and OpenRouter's
 * `/models` entries (`reasoning.supported_efforts`). Null means the source states nothing.
 */
object ReasoningCapabilities {
    private val EFFORT_FLAGS =
        listOf(
            "none" to "supports_none_reasoning_effort",
            "minimal" to "supports_minimal_reasoning_effort",
            "low" to "supports_low_reasoning_effort",
            "xhigh" to "supports_xhigh_reasoning_effort",
            "max" to "supports_max_reasoning_effort",
        )

    /**
     * LiteLLM's rules (router_utils/reasoning_effort_capability.py): a declared
     * `reasoning_effort_levels` list wins whole; otherwise medium and high are unconditional,
     * none/minimal/low are opt-out, and xhigh/max are opt-in. An entry with no flags says nothing.
     */
    fun fromLiteLlm(info: JsonObject): List<String>? {
        (info["reasoning_effort_levels"] as? JsonArray)?.let {
            return ordered(it)
        }
        if (bool(info, "supports_reasoning") == false) return emptyList()
        val flags = EFFORT_FLAGS.associate { (level, key) -> level to bool(info, key) }
        if (flags.values.all { it == null }) return null
        return ReasoningEffort.LEVELS.filter { level ->
            when (level) {
                "medium",
                "high" -> true
                "xhigh",
                "max" -> flags[level] == true
                else -> flags[level] != false
            }
        }
    }

    /**
     * OpenRouter: `reasoning.supported_efforts` lists the levels; a model whose
     * `supported_parameters` has neither `reasoning` nor `reasoning_effort` takes none.
     */
    fun fromOpenRouter(model: JsonObject): List<String>? {
        val reasoning = model["reasoning"] as? JsonObject
        (reasoning?.get("supported_efforts") as? JsonArray)
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                return ordered(it)
            }
        val parameters = model["supported_parameters"] as? JsonArray ?: return null
        val names = parameters.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        return if ("reasoning" !in names && "reasoning_effort" !in names) emptyList() else null
    }

    /** Whichever convention [model] uses, OpenRouter first. */
    fun fromModelEntry(model: JsonObject): List<String>? =
        fromOpenRouter(model) ?: fromLiteLlm(model)

    /** LiteLLM `model_info` fields for [levels], with flags matching the declared list. */
    fun liteLlmFields(levels: List<String>): Map<String, Any> {
        val known = ordered(levels)
        return buildMap {
            put("supports_reasoning", known.isNotEmpty())
            if (known.isEmpty()) return@buildMap
            put("reasoning_effort_levels", known)
            EFFORT_FLAGS.forEach { (level, key) -> put(key, level in known) }
        }
    }

    /** OpenRouter's `/models` `reasoning` object for [levels], or null for a model without any. */
    fun openRouterField(levels: List<String>): Map<String, Any>? {
        val known = ordered(levels)
        if (known.isEmpty()) return null
        return linkedMapOf(
            "mandatory" to ("none" !in known),
            // OpenRouter lists the strongest level first.
            "supported_efforts" to known.reversed(),
        )
    }

    private fun ordered(values: JsonArray): List<String> =
        ordered(values.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })

    private fun ordered(values: List<String>): List<String> {
        val declared = values.map { it.trim().lowercase(Locale.ROOT) }.toSet()
        return ReasoningEffort.LEVELS.filter { it in declared }
    }

    private fun bool(item: JsonObject, key: String): Boolean? =
        (item[key] as? JsonPrimitive)?.booleanOrNull
}
