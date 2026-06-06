package de.moritzf.quota.idea.mcp

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class McpYamlTargetUpdater {
    fun updateFile(yamlFilePath: String, propertyPath: String, value: String): Boolean {
        val file = McpJsonTargetUpdater.resolveJsonFilePath(yamlFilePath)
        require(Files.exists(file)) { "YAML file does not exist: $file" }
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
        val segments = McpJsonTargetUpdater.parsePropertyPath(propertyPath)
        val root = parseRoot(content)
        requireExistingTargetValue(root, segments)

        val lines = content.lines().toMutableList()
        val propertyIndex = findPropertyLine(lines, segments)
            ?: error("YAML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(segments)}")
        lines[propertyIndex] = replaceYamlStringValue(lines[propertyIndex], value)
        return lines.joinToString("\n")
    }

    companion object {
        private val loader = Load(LoadSettings.builder().build())

        fun validateTargetFile(yamlFilePath: String, propertyPath: String): McpJsonTargetValidationError? {
            val file = McpJsonTargetUpdater.resolveJsonFilePath(yamlFilePath)
            if (!Files.exists(file)) {
                return McpJsonTargetValidationError(
                    McpJsonTargetValidationProblem.FILE,
                    "YAML file does not exist: $file",
                )
            }

            val content = runCatching { Files.readString(file, StandardCharsets.UTF_8) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.FILE,
                        error.message ?: "Could not read YAML file: $file",
                    )
                }
            return validateTargetContent(content, propertyPath)
        }

        fun validateTargetContent(content: String, propertyPath: String): McpJsonTargetValidationError? {
            val segments = runCatching { McpJsonTargetUpdater.parsePropertyPath(propertyPath) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.PROPERTY,
                        error.message ?: "YAML property path is invalid.",
                    )
                }
            val root = runCatching { parseRoot(content) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.FILE,
                        error.message ?: "Could not parse YAML file.",
                    )
                }
            return runCatching {
                requireExistingTargetValue(root, segments)
                require(findPropertyLine(content.lines(), segments) != null) {
                    "YAML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(segments)}"
                }
            }.fold(
                onSuccess = { null },
                onFailure = { error ->
                    McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.PROPERTY,
                        error.message ?: "YAML property path is invalid.",
                    )
                },
            )
        }

        fun collectStringPropertyPaths(content: String): List<String> {
            val root = parseRoot(content)
            val paths = mutableListOf<String>()
            collectStringPropertyPaths(root, emptyList(), paths)
            return paths
        }

        private fun collectStringPropertyPaths(value: Any?, prefix: List<String>, paths: MutableList<String>) {
            when (value) {
                is String -> paths += McpJsonTargetUpdater.formatDotPath(prefix)
                is Map<*, *> -> value.forEach { (key, childValue) ->
                    val keyString = key as? String ?: return@forEach
                    collectStringPropertyPaths(childValue, prefix + keyString, paths)
                }
            }
        }

        private fun parseRoot(content: String): Any? {
            require(content.isNotBlank()) { "YAML file is empty." }
            return loader.loadFromString(content) ?: error("YAML file is empty.")
        }

        private fun requireExistingTargetValue(root: Any?, path: List<String>): Any? {
            var current = root
            path.forEachIndexed { index, segment ->
                val currentMap = current as? Map<*, *>
                    ?: error("YAML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(path.take(index))} is not a map")
                current = currentMap[segment]
                    ?: error("YAML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(path)}")
            }
            require(current is String) { "YAML property path must point to a string value." }
            return current
        }

        private fun findPropertyLine(lines: List<String>, path: List<String>): Int? {
            val stack = mutableListOf<YamlContext>()
            lines.forEachIndexed { index, line ->
                val parsed = parseLine(line) ?: return@forEachIndexed
                while (stack.isNotEmpty() && parsed.indent <= stack.last().indent) {
                    stack.removeAt(stack.lastIndex)
                }

                val currentPath = stack.map { it.key } + parsed.key
                if (currentPath == path && parsed.hasValue) {
                    return index
                }
                if (!parsed.hasValue) {
                    stack += YamlContext(parsed.indent, parsed.key)
                }
            }
            return null
        }

        private fun parseLine(line: String): YamlLine? {
            val content = line.substringBeforeComment()
            if (content.isBlank() || content.trimStart().startsWith('-')) {
                return null
            }
            val indent = content.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
            val trimmed = content.trimStart()
            val colonIndex = trimmed.indexOfUnquoted(':')
            if (colonIndex < 0) {
                return null
            }
            val key = parseYamlKey(trimmed.take(colonIndex).trim()) ?: return null
            return YamlLine(indent, key, trimmed.drop(colonIndex + 1).isNotBlank())
        }

        private fun parseYamlKey(key: String): String? {
            return when {
                key.startsWith('"') -> parseQuotedKey(key, '"')
                key.startsWith('\'') -> parseQuotedKey(key, '\'')
                key.isNotBlank() -> key
                else -> null
            }
        }

        private fun parseQuotedKey(key: String, quote: Char): String? {
            val current = StringBuilder()
            var escaping = false
            for (index in 1 until key.length) {
                val char = key[index]
                when {
                    escaping -> {
                        current.append(char)
                        escaping = false
                    }

                    quote == '"' && char == '\\' -> escaping = true
                    char == quote -> return current.toString()
                    else -> current.append(char)
                }
            }
            return null
        }

        private fun replaceYamlStringValue(line: String, value: String): String {
            val commentIndex = line.commentIndexAfter(0)
            val content = if (commentIndex >= 0) line.substring(0, commentIndex).trimEnd() else line.trimEnd()
            val suffix = if (commentIndex >= 0) line.substring(commentIndex) else ""
            val colonIndex = content.indexOfUnquoted(':')
            require(colonIndex >= 0) { "YAML property line is invalid." }
            return content.substring(0, colonIndex + 1) + " " + quoteYamlString(value) + if (suffix.isBlank()) "" else " " + suffix.trimStart()
        }

        private fun quoteYamlString(value: String): String {
            return buildString {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\b' -> append("\\b")
                        '\t' -> append("\\t")
                        '\n' -> append("\\n")
                        '\u000C' -> append("\\f")
                        '\r' -> append("\\r")
                        else -> append(char)
                    }
                }
                append('"')
            }
        }

        private fun String.substringBeforeComment(): String {
            val commentIndex = commentIndexAfter(0)
            return if (commentIndex >= 0) substring(0, commentIndex) else this
        }

        private fun String.commentIndexAfter(startIndex: Int): Int {
            var inString: Char? = null
            var escaping = false
            for (index in startIndex until length) {
                val char = this[index]
                when {
                    escaping -> escaping = false
                    inString == '"' && char == '\\' -> escaping = true
                    inString != null && char == inString -> inString = null
                    inString == null && (char == '"' || char == '\'') -> inString = char
                    inString == null && char == '#' -> return index
                }
            }
            return -1
        }

        private fun String.indexOfUnquoted(target: Char): Int {
            var inString: Char? = null
            var escaping = false
            forEachIndexed { index, char ->
                when {
                    escaping -> escaping = false
                    inString == '"' && char == '\\' -> escaping = true
                    inString != null && char == inString -> inString = null
                    inString == null && (char == '"' || char == '\'') -> inString = char
                    inString == null && char == target -> return index
                }
            }
            return -1
        }

        private data class YamlLine(
            val indent: Int,
            val key: String,
            val hasValue: Boolean,
        )

        private data class YamlContext(
            val indent: Int,
            val key: String,
        )
    }
}
