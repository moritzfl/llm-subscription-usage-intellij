package de.moritzf.quota.idea.mcp

import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class McpTomlTargetUpdater {
    fun updateFile(tomlFilePath: String, propertyPath: String, value: String): Boolean {
        val file = McpJsonTargetUpdater.resolveJsonFilePath(tomlFilePath)
        require(Files.exists(file)) { "TOML file does not exist: $file" }
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
            ?: error("TOML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(segments)}")
        lines[propertyIndex] = replaceTomlStringValue(lines[propertyIndex], value)
        return lines.joinToString("\n")
    }

    companion object {
        fun validateTargetFile(tomlFilePath: String, propertyPath: String): McpJsonTargetValidationError? {
            val file = McpJsonTargetUpdater.resolveJsonFilePath(tomlFilePath)
            if (!Files.exists(file)) {
                return McpJsonTargetValidationError(
                    McpJsonTargetValidationProblem.FILE,
                    "TOML file does not exist: $file",
                )
            }

            val content = runCatching { Files.readString(file, StandardCharsets.UTF_8) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.FILE,
                        error.message ?: "Could not read TOML file: $file",
                    )
                }
            return validateTargetContent(content, propertyPath)
        }

        fun validateTargetContent(content: String, propertyPath: String): McpJsonTargetValidationError? {
            val segments = runCatching { McpJsonTargetUpdater.parsePropertyPath(propertyPath) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.PROPERTY,
                        error.message ?: "TOML property path is invalid.",
                    )
                }
            val root = runCatching { parseRoot(content) }
                .getOrElse { error ->
                    return McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.FILE,
                        error.message ?: "Could not parse TOML file.",
                    )
                }
            return runCatching {
                requireExistingTargetValue(root, segments)
                require(findPropertyLine(content.lines(), segments) != null) {
                    "TOML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(segments)}"
                }
            }.fold(
                onSuccess = { null },
                onFailure = { error ->
                    McpJsonTargetValidationError(
                        McpJsonTargetValidationProblem.PROPERTY,
                        error.message ?: "TOML property path is invalid.",
                    )
                },
            )
        }

        private fun parseRoot(content: String): TomlParseResult {
            require(content.isNotBlank()) { "TOML file is empty." }
            val root = Toml.parse(content)
            require(!root.hasErrors()) {
                root.errors().joinToString("; ") { it.message ?: "Could not parse TOML file." }
            }
            return root
        }

        private fun requireExistingTargetValue(root: TomlTable, path: List<String>): Any? {
            var current: Any? = root
            path.forEachIndexed { index, segment ->
                val currentTable = current as? TomlTable
                    ?: error("TOML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(path.take(index))} is not a table")
                current = currentTable.get(segment)
                    ?: error("TOML property path does not exist: ${McpJsonTargetUpdater.formatDotPath(path)}")
            }
            require(current is String) { "TOML property path must point to a string value." }
            return current
        }

        private fun findPropertyLine(lines: List<String>, path: List<String>): Int? {
            val tablePath = path.dropLast(1)
            val property = path.last()
            var currentTable: List<String> = emptyList()
            lines.forEachIndexed { index, line ->
                parseTableHeader(line)?.let { header ->
                    currentTable = header
                    return@forEachIndexed
                }
                if (currentTable == tablePath && parsePropertyKey(line) == property) {
                    return index
                }
            }
            return null
        }

        private fun parseTableHeader(line: String): List<String>? {
            val trimmed = line.trim()
            if (!trimmed.startsWith('[') || !trimmed.endsWith(']') || trimmed.startsWith("[[")) {
                return null
            }
            val inner = trimmed.drop(1).dropLast(1).trim()
            return parseBareOrQuotedPath(inner)
        }

        private fun parsePropertyKey(line: String): String? {
            val content = line.substringBeforeComment().trimStart()
            if (content.isBlank() || content.startsWith('[')) {
                return null
            }
            val equalsIndex = content.indexOfUnquoted('=')
            if (equalsIndex < 0) {
                return null
            }
            val key = content.take(equalsIndex).trim()
            return if (key.startsWith('"')) {
                parseQuotedKey(key)
            } else {
                parseBareOrQuotedPath(key).singleOrNull()
            }
        }

        private fun parseQuotedKey(key: String): String? {
            val current = StringBuilder()
            var escaping = false
            for (index in 1 until key.length) {
                val char = key[index]
                when {
                    escaping -> {
                        current.append(char)
                        escaping = false
                    }

                    char == '\\' -> escaping = true
                    char == '"' -> return current.toString()
                    else -> current.append(char)
                }
            }
            return null
        }

        private fun parseBareOrQuotedPath(value: String): List<String> {
            val segments = mutableListOf<String>()
            val current = StringBuilder()
            var inString = false
            var escaping = false
            value.forEach { char ->
                when {
                    escaping -> {
                        current.append(char)
                        escaping = false
                    }

                    inString && char == '\\' -> escaping = true
                    char == '"' -> inString = !inString
                    !inString && char == '.' -> {
                        segments += current.toString().trim()
                        current.clear()
                    }

                    else -> current.append(char)
                }
            }
            segments += current.toString().trim()
            return segments.map { it.removeSurrounding("\"") }.filter { it.isNotBlank() }
        }

        private fun replaceTomlStringValue(line: String, value: String): String {
            val equalsIndex = line.indexOfUnquoted('=')
            require(equalsIndex >= 0) { "TOML property line is invalid." }
            val commentIndex = line.commentIndexAfter(equalsIndex + 1)
            val suffix = if (commentIndex >= 0) line.substring(commentIndex) else ""
            return line.substring(0, equalsIndex + 1) + " " + quoteTomlString(value) + if (suffix.isBlank()) "" else " " + suffix.trimStart()
        }

        private fun quoteTomlString(value: String): String {
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
            var inString = false
            var escaping = false
            for (index in startIndex until length) {
                val char = this[index]
                when {
                    escaping -> escaping = false
                    inString && char == '\\' -> escaping = true
                    char == '"' -> inString = !inString
                    !inString && char == '#' -> return index
                }
            }
            return -1
        }

        private fun String.indexOfUnquoted(target: Char): Int {
            var inString = false
            var escaping = false
            forEachIndexed { index, char ->
                when {
                    escaping -> escaping = false
                    inString && char == '\\' -> escaping = true
                    char == '"' -> inString = !inString
                    !inString && char == target -> return index
                }
            }
            return -1
        }
    }
}
