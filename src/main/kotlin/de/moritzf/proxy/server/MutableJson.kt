package de.moritzf.proxy.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Mutable JSON object builder that wraps an immutable [JsonObject].
 * Provides Jackson-like in-place mutation semantics ([put], [set], [remove])
 * while building on kotlinx.serialization under the hood.
 *
 * Call [build] to produce the immutable [JsonObject].
 */
class MutableJsonObject(initial: JsonObject = JsonObject(emptyMap())) {

    private val entries: MutableMap<String, JsonElement> = initial.toMutableMap()

    fun put(key: String, value: String?): MutableJsonObject = apply {
        entries[key] = if (value == null) JsonNull else JsonPrimitive(value)
    }

    fun put(key: String, value: Int): MutableJsonObject = apply {
        entries[key] = JsonPrimitive(value)
    }

    fun put(key: String, value: Long): MutableJsonObject = apply {
        entries[key] = JsonPrimitive(value)
    }

    fun put(key: String, value: Boolean): MutableJsonObject = apply {
        entries[key] = JsonPrimitive(value)
    }

    fun put(key: String, value: Double): MutableJsonObject = apply {
        entries[key] = JsonPrimitive(value)
    }

    fun put(key: String, value: JsonElement): MutableJsonObject = apply {
        entries[key] = value
    }

    fun putNull(key: String): MutableJsonObject = apply {
        entries[key] = JsonNull
    }

    fun set(key: String, value: JsonElement): MutableJsonObject = apply {
        entries[key] = value
    }

    fun set(key: String, value: MutableJsonObject): MutableJsonObject = apply {
        entries[key] = value.build()
    }

    fun set(key: String, value: MutableJsonArray): MutableJsonObject = apply {
        entries[key] = value.build()
    }

    fun remove(key: String): MutableJsonObject = apply {
        entries.remove(key)
    }

    fun has(key: String): Boolean = entries.containsKey(key) && entries[key] !is JsonNull

    fun hasNonNull(key: String): Boolean = has(key)

    fun get(key: String): JsonElement? = entries[key]

    fun path(key: String): JsonElement? = entries[key] ?: JsonNull

    fun pathOrNull(key: String): JsonElement? = entries[key]

    fun isObject(): Boolean = true

    fun build(): JsonObject = JsonObject(entries.toMap())

    fun deepCopy(): MutableJsonObject = MutableJsonObject(build())

    companion object {
        fun from(element: JsonElement?): MutableJsonObject? {
            return if (element is JsonObject) MutableJsonObject(element) else null
        }
    }
}

/**
 * Mutable JSON array builder that wraps an immutable [JsonArray].
 * Provides Jackson-like in-place mutation semantics ([add], [addAll])
 * while building on kotlinx.serialization under the hood.
 *
 * Call [build] to produce the immutable [JsonArray].
 */
class MutableJsonArray(initial: JsonArray = JsonArray(emptyList())) {

    private val elements: MutableList<JsonElement> = initial.toMutableList()

    fun add(value: JsonElement): MutableJsonArray = apply {
        elements.add(value)
    }

    fun add(value: MutableJsonObject): MutableJsonArray = apply {
        elements.add(value.build())
    }

    fun add(value: MutableJsonArray): MutableJsonArray = apply {
        elements.add(value.build())
    }

    fun add(value: String): MutableJsonArray = apply {
        elements.add(JsonPrimitive(value))
    }

    fun add(value: Int): MutableJsonArray = apply {
        elements.add(JsonPrimitive(value))
    }

    fun add(value: Long): MutableJsonArray = apply {
        elements.add(JsonPrimitive(value))
    }

    fun add(value: Boolean): MutableJsonArray = apply {
        elements.add(JsonPrimitive(value))
    }

    fun addAll(elements: Iterable<JsonElement>): MutableJsonArray = apply {
        this.elements.addAll(elements)
    }

    fun addAll(vararg elements: JsonElement): MutableJsonArray = apply {
        this.elements.addAll(elements)
    }

    fun addAll(other: MutableJsonArray): MutableJsonArray = apply {
        this.elements.addAll(other.elements)
    }

    fun removeIf(predicate: (JsonElement) -> Boolean): MutableJsonArray = apply {
        elements.removeAll(predicate)
    }

    fun removeAll(): MutableJsonArray = apply {
        elements.clear()
    }

    fun isEmpty(): Boolean = elements.isEmpty()

    fun size(): Int = elements.size

    fun iterator(): Iterator<JsonElement> = elements.iterator()

    fun forEach(action: (JsonElement) -> Unit) {
        elements.forEach(action)
    }

    fun map(mapper: (JsonElement) -> JsonElement): MutableJsonArray = MutableJsonArray(JsonArray(elements.map(mapper)))

    fun filter(predicate: (JsonElement) -> Boolean): MutableJsonArray = MutableJsonArray(JsonArray(elements.filter(predicate)))

    fun get(index: Int): JsonElement? = elements.getOrNull(index)

    fun build(): JsonArray = JsonArray(elements.toList())

    fun deepCopy(): MutableJsonArray = MutableJsonArray(build())

    companion object {
        fun from(element: JsonElement?): MutableJsonArray? {
            return if (element is JsonArray) MutableJsonArray(element) else null
        }
    }
}

/**
 * Convenience factory for creating a mutable JSON object.
 */
fun createObjectNode(): MutableJsonObject = MutableJsonObject()

/**
 * Convenience factory for creating a mutable JSON array.
 */
fun createArrayNode(): MutableJsonArray = MutableJsonArray()
