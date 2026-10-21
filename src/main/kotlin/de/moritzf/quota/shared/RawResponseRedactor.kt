package de.moritzf.quota.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal object RawResponseRedactor {
    private const val REDACTED = "[REDACTED]"
    private val exactSensitiveNames =
        setOf(
            "authorization",
            "proxy_authorization",
            "cookie",
            "set_cookie",
            "access_token",
            "refresh_token",
            "id_token",
            "api_key",
            "apikey",
            "credentials",
            "password",
            "secret",
        )
    private val camelCase = Regex("([a-z])([A-Z])")
    // Generic key/token fields can be hashes, cache IDs, or pagination cursors, not credentials.
    private val credentialSuffixes =
        listOf(
            "_api_key",
            "_access_token",
            "_refresh_token",
            "_id_token",
            "_session_token",
            "_csrf_token",
            "_secret",
            "_password",
            "_cookie",
        )
    // Java's header validation can echo the entire malformed value, including quotes/newlines.
    private val invalidHeader = Regex("(?is)(invalid header value:\\s*).*")
    private const val DIAGNOSTIC_VALUE =
        """(\[REDACTED]|"(?:\\.|[^"\\])*(?:"|$)|'(?:\\.|[^'\\])*(?:'|$)|[^\s,;&}\]]+)"""
    private val authScheme = Regex("(?i)\\b(Bearer|Basic)\\s+$DIAGNOSTIC_VALUE")
    private val sensitiveHeader =
        Regex("(?im)\\b((?:proxy-)?authorization|(?:set-)?cookie)(\\s*[:=]\\s*)[^\\r\\n]+")
    private val assignment = Regex("""(["']?)([\w-]+)\1(\s*[:=]\s*)""")
    private val diagnosticValue = Regex(DIAGNOSTIC_VALUE)

    fun redact(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return redactText(raw)
    }

    fun redact(
        element: JsonElement,
        extraSensitiveNames: Set<String> = emptySet(),
        secret: String? = null,
    ): JsonElement = redactElement(element, extraSensitiveNames, secret, 0)

    private fun redactElement(
        element: JsonElement,
        extraSensitiveNames: Set<String>,
        secret: String?,
        depth: Int,
        sensitiveValue: Boolean = false,
    ): JsonElement =
        when (element) {
            is JsonObject ->
                buildJsonObject {
                    element.forEach { (key, value) ->
                        put(
                            key,
                            redactElement(
                                value,
                                extraSensitiveNames,
                                secret,
                                depth,
                                isSensitiveName(key, extraSensitiveNames),
                            ),
                        )
                    }
                }
            is JsonArray ->
                buildJsonArray {
                    element.forEach {
                        add(redactElement(it, extraSensitiveNames, secret, depth, sensitiveValue))
                    }
                }
            is JsonPrimitive ->
                if (element.isString)
                    JsonPrimitive(
                        if (sensitiveValue) REDACTED
                        else redactText(element.content, secret, extraSensitiveNames, depth + 1)
                    )
                else element
        }

    /** Sanitizes diagnostics, including JSON bodies embedded in exception messages. */
    fun redactText(text: String, secret: String? = null): String =
        redactText(text, secret, emptySet(), 0)

    private fun redactText(
        text: String,
        secret: String?,
        extraSensitiveNames: Set<String>,
        depth: Int,
    ): String {
        // Bound repeated decoding of JSON encoded inside JSON strings.
        if (depth > 32) return REDACTED
        val element = parseDocument(text)
        if (element != null) {
            val redacted = redactElement(element, extraSensitiveNames, secret, depth)
            return if (redacted == element) text else redacted.toString()
        }
        var result = if (secret.isNullOrBlank()) text else text.replace(secret, REDACTED)
        invalidHeader.find(result)?.let {
            return redactText(
                result.substring(0, it.range.first),
                secret,
                extraSensitiveNames,
                depth + 1,
            ) + it.groupValues[1] + REDACTED
        }
        // HTTP errors often prefix a complete JSON body with a status and description.
        val start = result.indexOfFirst { it == '{' || it == '[' }
        if (start >= 0) {
            val body = result.substring(start)
            val json = parseDocument(body)
            if (json != null) {
                return redactText(
                    result.substring(0, start),
                    secret,
                    extraSensitiveNames,
                    depth + 1,
                ) + redactText(body, secret, extraSensitiveNames, depth + 1)
            }
        }
        result = authScheme.replace(result) { it.groupValues[1] + " " + REDACTED }
        result =
            sensitiveHeader.replace(result) { it.groupValues[1] + it.groupValues[2] + REDACTED }
        // Match labels separately so an ordinary prefix cannot swallow a nested secret assignment.
        val redacted = StringBuilder()
        var copied = 0
        for (match in assignment.findAll(result)) {
            if (
                match.range.first < copied ||
                    !isSensitiveName(match.groupValues[2], extraSensitiveNames)
            )
                continue
            val value = diagnosticValue.matchAt(result, match.range.last + 1) ?: continue
            redacted
                .append(result, copied, match.range.first)
                .append(match.value)
                .append('"')
                .append(REDACTED)
                .append('"')
            copied = value.range.last + 1
        }
        return redacted.append(result, copied, result.length).toString()
    }

    private fun parseDocument(text: String): JsonElement? {
        if (text.firstOrNull { !it.isWhitespace() } !in listOf('{', '[', '"')) return null
        return runCatching { JsonSupport.json.parseToJsonElement(text) }.getOrNull()
    }

    private fun isSensitiveName(name: String, extraSensitiveNames: Set<String>): Boolean {
        val normalized = name.replace(camelCase, "$1_$2").lowercase().replace('-', '_')
        return normalized in exactSensitiveNames ||
            name in extraSensitiveNames ||
            normalized in extraSensitiveNames ||
            credentialSuffixes.any(normalized::endsWith)
    }
}
