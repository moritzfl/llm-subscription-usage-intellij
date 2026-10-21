package de.moritzf.quota.opencode.proxy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Normalizes standard JSON Schema for Gemini's `parametersJsonSchema`.
 *
 * Gemini rejects a few shapes common in published OpenAI tool schemas. This mirrors OpenCode's
 * Gemini schema lowering: draft-04 boolean bounds, draft-07 tuples, undeclared `required` names,
 * and recursive `$ref` loops are rewritten; everything else is passed through unchanged.
 */
internal object GeminiJsonSchema {
    private val SCHEMA_MAPS =
        setOf(
            "properties",
            "patternProperties",
            "\$defs",
            "definitions",
            "dependentSchemas",
            "dependencies",
        )
    private val VALUES = setOf("const", "default", "enum", "examples", "dependentRequired")

    fun normalize(schema: JsonElement?): JsonObject {
        if (schema == null || schema is JsonNull) return emptyObjectSchema()
        val normalized = normalizeNode(schema) as? JsonObject ?: return emptyObjectSchema()
        val defs = buildJsonObject {
            normalized.forEach { (key, value) ->
                if ((key == "\$defs" || key == "definitions") && value is JsonObject) {
                    put(
                        key,
                        buildJsonObject {
                            value.forEach { (name, definition) ->
                                put(name, cutLoops(definition, "#/$key/$name", false))
                            }
                        },
                    )
                } else {
                    put(key, value)
                }
            }
        }
        return cutLoops(defs, "#", false) as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun normalizeNode(schema: JsonElement?): JsonElement {
        return when (schema) {
            is JsonArray -> buildJsonArray { schema.forEach { add(normalizeNode(it)) } }
            is JsonObject -> normalizeObject(schema)
            null -> JsonNull
            else -> schema
        }
    }

    private fun normalizeObject(schema: JsonObject): JsonObject {
        val properties = schema["properties"] as? JsonObject
        return buildJsonObject {
            schema.forEach { (key, value) ->
                when {
                    key in VALUES -> put(key, value)
                    key in SCHEMA_MAPS && value is JsonObject ->
                        put(
                            key,
                            buildJsonObject {
                                value.forEach { (name, child) -> put(name, normalizeNode(child)) }
                            },
                        )

                    key == "required" && properties != null && value is JsonArray -> {
                        put(
                            key,
                            buildJsonArray {
                                value.forEach { name ->
                                    val text = (name as? JsonPrimitive)?.contentOrNull
                                    if (text != null && text in properties) add(name)
                                }
                            },
                        )
                    }

                    key == "exclusiveMinimum" &&
                        value is JsonPrimitive &&
                        value.booleanOrNull == true -> {
                        (schema["minimum"] as? JsonPrimitive)
                            ?.takeIf { it.doubleOrNull() != null }
                            ?.let { put(key, it) }
                    }

                    key == "exclusiveMaximum" &&
                        value is JsonPrimitive &&
                        value.booleanOrNull == true -> {
                        (schema["maximum"] as? JsonPrimitive)
                            ?.takeIf { it.doubleOrNull() != null }
                            ?.let { put(key, it) }
                    }

                    (key == "minimum" || key == "maximum") &&
                        (schema[if (key == "minimum") "exclusiveMinimum" else "exclusiveMaximum"]
                                as? JsonPrimitive)
                            ?.booleanOrNull == true -> {}

                    key == "exclusiveMinimum" || key == "exclusiveMaximum" -> {
                        if ((value as? JsonPrimitive)?.booleanOrNull == null) {
                            put(key, normalizeNode(value))
                        }
                    }
                    key == "items" && value is JsonArray ->
                        put(
                            "prefixItems",
                            buildJsonArray { value.forEach { add(normalizeNode(it)) } },
                        )

                    key == "additionalItems" && schema["items"] is JsonArray ->
                        put("items", normalizeNode(value))

                    else -> put(key, normalizeNode(value))
                }
            }
        }
    }

    private fun cutLoops(schema: JsonElement?, target: String, safe: Boolean): JsonElement {
        if (schema is JsonArray) {
            return buildJsonArray { schema.forEach { add(cutLoops(it, target, safe)) } }
        }
        val objectValue = schema as? JsonObject ?: return schema ?: JsonNull
        if ((objectValue["\$ref"] as? JsonPrimitive)?.contentOrNull == target && !safe) {
            return JsonObject(emptyMap())
        }
        val required = objectValue["required"] as? JsonArray
        return buildJsonObject {
            objectValue.forEach { (key, value) ->
                when {
                    key in VALUES -> put(key, value)
                    key == "items" ->
                        put(
                            key,
                            cutLoops(
                                value,
                                target,
                                safe ||
                                    ((objectValue["minItems"] as? JsonPrimitive)?.intOrNull ?: 0) <=
                                        0,
                            ),
                        )

                    key == "properties" && value is JsonObject ->
                        put(
                            key,
                            buildJsonObject {
                                value.forEach { (name, child) ->
                                    val isRequired =
                                        required?.any {
                                            (it as? JsonPrimitive)?.contentOrNull == name
                                        } == true
                                    put(name, cutLoops(child, target, safe || !isRequired))
                                }
                            },
                        )

                    key in SCHEMA_MAPS && value is JsonObject ->
                        put(
                            key,
                            buildJsonObject {
                                value.forEach { (name, child) ->
                                    put(name, cutLoops(child, target, safe))
                                }
                            },
                        )

                    else -> put(key, cutLoops(value, target, safe))
                }
            }
        }
    }

    private fun JsonPrimitive.doubleOrNull(): Double? = contentOrNull?.toDoubleOrNull()

    private fun emptyObjectSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(emptyMap()))
    }
}
