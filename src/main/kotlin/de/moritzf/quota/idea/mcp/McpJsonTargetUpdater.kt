package de.moritzf.quota.idea.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@OptIn(ExperimentalSerializationApi::class)
class McpJsonTargetUpdater(
    private val json: Json = Json {
        allowComments = true
        allowTrailingComma = true
        prettyPrint = true
        prettyPrintIndent = "  "
    },
) {
    fun updateFile(jsonFilePath: String, propertyPath: String, value: String): Boolean {
        val file = resolveJsonFilePath(jsonFilePath)
        val existing = if (Files.exists(file)) Files.readString(file, StandardCharsets.UTF_8) else ""
        val updated = updateContent(existing, propertyPath, value)
        if (existing == updated) {
            return false
        }

        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, updated, StandardCharsets.UTF_8)
        return true
    }

    fun updateContent(content: String, propertyPath: String, value: String): String {
        val segments = parsePropertyPath(propertyPath)
        val root = parseRoot(content)
        val updated = setValue(root, segments, JsonPrimitive(value))
        return json.encodeToString(JsonElement.serializer(), updated) + "\n"
    }

    private fun parseRoot(content: String): JsonElement {
        if (content.isBlank()) {
            return buildJsonObject { }
        }
        return json.parseToJsonElement(content).takeIf { it is JsonObject } ?: buildJsonObject { }
    }

    private fun setValue(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        val rootObject = root as? JsonObject ?: buildJsonObject { }
        return setObjectValue(rootObject, path, value)
    }

    private fun setObjectValue(source: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val replacement = if (path.size == 1) {
            value
        } else {
            val child = source[key] as? JsonObject ?: buildJsonObject { }
            setObjectValue(child, path.drop(1), value)
        }

        return buildJsonObject {
            var replaced = false
            source.forEach { (existingKey, existingValue) ->
                if (existingKey == key) {
                    put(existingKey, replacement)
                    replaced = true
                } else {
                    put(existingKey, existingValue)
                }
            }
            if (!replaced) {
                put(key, replacement)
            }
        }
    }

    companion object {
        fun resolveJsonFilePath(rawPath: String): Path {
            val trimmed = rawPath.trim()
            val expanded = when {
                trimmed == "~" -> System.getProperty("user.home")
                trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.substring(1)
                else -> trimmed
            }
            return Paths.get(expanded).toAbsolutePath().normalize()
        }

        fun parsePropertyPath(rawPath: String): List<String> {
            val trimmed = rawPath.trim()
            require(trimmed.isNotEmpty()) { "JSON property path must not be blank" }

            val segments = if (trimmed.startsWith('/')) {
                trimmed.drop(1)
                    .split('/')
                    .map { segment -> segment.replace("~1", "/").replace("~0", "~") }
            } else {
                parseDotPath(trimmed)
            }

            require(segments.all { it.isNotBlank() }) { "JSON property path contains an empty segment" }
            return segments
        }

        private fun parseDotPath(path: String): List<String> {
            val segments = mutableListOf<String>()
            val current = StringBuilder()
            var escaping = false

            path.forEach { char ->
                when {
                    escaping -> {
                        current.append(char)
                        escaping = false
                    }

                    char == '\\' -> escaping = true
                    char == '.' -> {
                        segments += current.toString()
                        current.clear()
                    }

                    else -> current.append(char)
                }
            }
            if (escaping) {
                current.append('\\')
            }
            segments += current.toString()
            return segments
        }
    }
}
