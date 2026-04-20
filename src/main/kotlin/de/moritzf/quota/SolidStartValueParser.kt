package de.moritzf.quota

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class SolidStartValueParser(
    private val text: String,
    startIndex: Int,
) {
    private var index: Int = startIndex
    private val references = mutableMapOf<Int, JsonElement>()

    fun parseValue(): JsonElement {
        skipWhitespace()
        if (index >= text.length) {
            fail("Unexpected end of input")
        }

        return when (text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonPrimitive(parseString())
            '-' -> parseNumber()
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> parseNumber()
            '!' -> parseBooleanBang()
            'n' -> parseNull()
            't', 'f' -> parseBooleanWord()
            '$' -> parseReferenceExpression()
            else -> fail("Unexpected token '${text[index]}'")
        }
    }

    private fun parseObject(): JsonObject {
        expect('{')
        skipWhitespace()
        val content = linkedMapOf<String, JsonElement>()
        if (peek('}')) {
            index++
            return JsonObject(content)
        }

        while (true) {
            skipWhitespace()
            val key = parseObjectKey()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            content[key] = value
            skipWhitespace()
            if (peek('}')) {
                index++
                return JsonObject(content)
            }
            expect(',')
        }
    }

    private fun parseArray(): JsonArray {
        expect('[')
        skipWhitespace()
        val elements = mutableListOf<JsonElement>()
        if (peek(']')) {
            index++
            return JsonArray(elements)
        }

        while (true) {
            elements += parseValue()
            skipWhitespace()
            if (peek(']')) {
                index++
                return JsonArray(elements)
            }
            expect(',')
        }
    }

    private fun parseObjectKey(): String {
        skipWhitespace()
        if (peek('"')) {
            return parseString()
        }

        val start = index
        while (index < text.length && isIdentifierPart(text[index])) {
            index++
        }
        if (start == index) {
            fail("Expected object key")
        }
        return text.substring(start, index)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < text.length) {
            val ch = text[index++]
            when (ch) {
                '"' -> return result.toString()
                '\\' -> {
                    if (index >= text.length) {
                        fail("Unterminated escape sequence")
                    }
                    val escaped = text[index++]
                    result.append(
                        when (escaped) {
                            '"', '\\', '/' -> escaped
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> parseUnicodeEscape()
                            else -> fail("Unsupported escape sequence: \\$escaped")
                        }
                    )
                }
                else -> result.append(ch)
            }
        }
        fail("Unterminated string literal")
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > text.length) {
            fail("Incomplete unicode escape")
        }
        val digits = text.substring(index, index + 4)
        index += 4
        return digits.toInt(16).toChar()
    }

    private fun parseNumber(): JsonPrimitive {
        val start = index
        if (peek('-')) {
            index++
        }
        val digitsStart = index
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        if (digitsStart == index) {
            fail("Expected numeric literal")
        }
        if (peek('.')) {
            index++
            while (index < text.length && text[index].isDigit()) {
                index++
            }
        }
        if (peek('e') || peek('E')) {
            index++
            if (peek('+') || peek('-')) {
                index++
            }
            while (index < text.length && text[index].isDigit()) {
                index++
            }
        }
        return JsonPrimitive(text.substring(start, index))
    }

    private fun parseBooleanBang(): JsonPrimitive {
        expect('!')
        return when {
            peek('0') -> {
                index++
                JsonPrimitive(true)
            }
            peek('1') -> {
                index++
                JsonPrimitive(false)
            }
            text.startsWith("true", index) -> {
                index += 4
                JsonPrimitive(true)
            }
            text.startsWith("false", index) -> {
                index += 5
                JsonPrimitive(false)
            }
            else -> fail("Unsupported bang literal")
        }
    }

    private fun parseBooleanWord(): JsonPrimitive {
        return when {
            text.startsWith("true", index) -> {
                index += 4
                JsonPrimitive(true)
            }
            text.startsWith("false", index) -> {
                index += 5
                JsonPrimitive(false)
            }
            else -> fail("Unsupported identifier literal")
        }
    }

    private fun parseNull(): JsonElement {
        if (!text.startsWith("null", index)) {
            fail("Unsupported identifier literal")
        }
        index += 4
        return JsonNull
    }

    private fun parseReferenceExpression(): JsonElement {
        expect('$')
        expect('R')
        expect('[')
        val referenceIndex = parseReferenceIndex()
        expect(']')
        skipWhitespace()

        if (peek('=')) {
            index++
            val assignedValue = parseValue()
            references[referenceIndex] = assignedValue
            return assignedValue
        }

        return references[referenceIndex]
            ?: fail("Reference \$R[$referenceIndex] used before assignment")
    }

    private fun parseReferenceIndex(): Int {
        skipWhitespace()
        if (peek('"')) {
            parseString()
            fail("String-based references are not supported in quota payload")
        }

        val start = index
        while (index < text.length && text[index].isDigit()) {
            index++
        }
        if (start == index) {
            fail("Expected reference index")
        }
        return text.substring(start, index).toInt()
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index++
        }
    }

    private fun expect(expected: Char) {
        if (!peek(expected)) {
            fail("Expected '$expected'")
        }
        index++
    }

    private fun peek(expected: Char): Boolean {
        return index < text.length && text[index] == expected
    }

    private fun isIdentifierPart(char: Char): Boolean {
        return char == '_' || char.isLetterOrDigit()
    }

    private fun fail(message: String): Nothing {
        throw OpenCodeQuotaException("$message at offset $index", 200, text)
    }
}
