package de.moritzf.quota.idea.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
        require(Files.exists(file)) { "JSON file does not exist: $file" }
        val existing = Files.readString(file, StandardCharsets.UTF_8)
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
        val root = parseRootObject(content, json)
        requireExistingTargetValue(root, segments)
        val updated = setExistingObjectValue(root, segments, JsonPrimitive(value))
        return json.encodeToString(JsonElement.serializer(), updated) + "\n"
    }

    private fun setExistingObjectValue(source: JsonObject, path: List<String>, value: JsonElement): JsonObject {
        val key = path.first()
        val replacement = if (path.size == 1) {
            value
        } else {
            val child = source[key] as? JsonObject
                ?: error("JSON property path does not exist: ${formatDotPath(path)}")
            setExistingObjectValue(child, path.drop(1), value)
        }

        return buildJsonObject {
            source.forEach { (existingKey, existingValue) ->
                if (existingKey == key) {
                    put(existingKey, replacement)
                } else {
                    put(existingKey, existingValue)
                }
            }
        }
    }

    companion object {
        private val validationJson = Json {
            allowComments = true
            allowTrailingComma = true
        }

        fun validateTargetFile(jsonFilePath: String, propertyPath: String): McpJsonTargetValidationError? {
            val file = resolveJsonFilePath(jsonFilePath)
            if (!Files.exists(file)) {
                return McpJsonTargetValidationError(
                    McpJsonTargetValidationProblem.FILE,
                    "JSON file does not exist: $file",
                )
            }

            val content = runCatching { Files.readString(file, StandardCharsets.UTF_8) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.FILE,
                        error.message ?: "Could not read JSON file: $file",
                    )
                }
            return validateTargetContent(content, propertyPath)
        }

        fun validateTargetContent(content: String, propertyPath: String): McpJsonTargetValidationError? {
            val segments = runCatching { parsePropertyPath(propertyPath) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.PROPERTY,
                        error.message ?: "JSON property path is invalid.",
                    )
                }
            val root = runCatching { parseRootObject(content, validationJson) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.FILE,
                        error.message ?: "Could not parse JSON file.",
                    )
                }
            return runCatching {
                requireExistingTargetValue(root, segments)
            }.fold(
                onSuccess = { null },
                onFailure = { error ->
                    McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.PROPERTY,
                        error.message ?: "JSON property path is invalid.",
                    )
                },
            )
        }

        fun isSupportedTargetValue(value: JsonElement): Boolean {
            return value is JsonNull || value is JsonPrimitive && value.isString
        }

        fun resolveJsonFilePath(rawPath: String): Path {
            val trimmed = rawPath.trim()
            val expanded = when {
                trimmed == "~" -> System.getProperty("user.home")
                trimmed.startsWith("~/") -> System.getProperty("user.home") + trimmed.substring(1)
                else -> trimmed
            }
            return Paths.get(expanded).toAbsolutePath().normalize()
        }

        private fun parseRootObject(content: String, parser: Json): JsonObject {
            require(content.isNotBlank()) { "JSON file is empty." }
            val root = parser.parseToJsonElement(content)
            require(root is JsonObject) { "Top-level JSON value must be an object." }
            return root
        }

        private fun requireExistingTargetValue(root: JsonObject, path: List<String>): JsonElement {
            var current: JsonElement = root
            path.forEachIndexed { index, segment ->
                val currentObject = current as? JsonObject
                    ?: error("JSON property path does not exist: ${formatDotPath(path.take(index))} is not an object")
                current = currentObject[segment]
                    ?: error("JSON property path does not exist: ${formatDotPath(path)}")
            }
            require(isSupportedTargetValue(current)) {
                "JSON property path must point to a string or null value."
            }
            return current
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

        fun formatDotPath(segments: List<String>): String {
            require(segments.isNotEmpty()) { "JSON property path must not be blank" }
            require(segments.all { it.isNotBlank() }) { "JSON property path contains an empty segment" }
            return segments.joinToString(".") { segment ->
                buildString {
                    segment.forEach { char ->
                        if (char == '.' || char == '\\') {
                            append('\\')
                        }
                        append(char)
                    }
                }
            }
        }

        fun findLikelyMcpServerPath(paths: Iterable<String>): String? {
            var bestPath: String? = null
            var bestScore = Int.MIN_VALUE
            paths.forEach { path ->
                val score = scoreLikelyMcpServerPath(path) ?: return@forEach
                if (score > bestScore) {
                    bestPath = path
                    bestScore = score
                }
            }
            return bestPath
        }

        private fun scoreLikelyMcpServerPath(path: String): Int? {
            val lowerPath = path.lowercase()
            if (!lowerPath.contains("mcp") || (!lowerPath.contains("intellij") && !lowerPath.contains("jetbrains"))) {
                return null
            }

            val segments = runCatching { parsePropertyPath(path) }.getOrDefault(path.split('.'))
            val lastSegment = segments.lastOrNull()?.lowercase().orEmpty()
            return segments.size + when {
                lastSegment == "url" -> 100
                lastSegment.contains("url") -> 80
                lastSegment.contains("endpoint") -> 60
                else -> 0
            }
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

data class McpJsonTargetValidationError(
    val problem: McpJsonTargetValidationProblem,
    val message: String,
)

enum class McpJsonTargetValidationProblem {
    FILE,
    PROPERTY,
}
