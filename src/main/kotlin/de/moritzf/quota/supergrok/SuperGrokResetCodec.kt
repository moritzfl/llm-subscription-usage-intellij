package de.moritzf.quota.supergrok

import kotlin.time.Instant

internal object SuperGrokResetCodec {
    private const val FIELD_TOKENS = 10
    private const val FIELD_TOKEN_ID = 10
    private const val FIELD_VALIDITY_END = 30
    private const val FIELD_TIMESTAMP_SECONDS = 1
    private const val FIELD_TIMESTAMP_NANOS = 2
    private const val WIRE_VARINT = 0
    private const val WIRE_LEN = 2

    fun emptyRequestFrame(): ByteArray = grpcWebFrame(ByteArray(0))

    fun redeemRequestFrame(tokenId: String): ByteArray {
        return grpcWebFrame(lengthDelimited(FIELD_TOKEN_ID, tokenId.encodeToByteArray()))
    }

    fun parseResetTokens(body: ByteArray, now: Instant): List<SuperGrokResetToken> {
        val payloads =
            grpcWebDataFrames(body).ifEmpty {
                if (looksLikeProtobuf(body)) listOf(body) else emptyList()
            }
        val tokens =
            payloads
                .flatMap { parseTokenList(it) }
                .filter { it.tokenId.isNotBlank() }
                .filter { token -> token.expiresAt == null || token.expiresAt > now }
        return tokens.distinctBy { it.tokenId }
    }

    fun grpcStatus(body: ByteArray, headers: Map<String, List<String>>): Pair<Int, String?> {
        val headerStatus = headerValue(headers, "grpc-status")?.toIntOrNull()
        val headerMessage = headerValue(headers, "grpc-message")
        if (headerStatus != null && headerStatus != 0) {
            return headerStatus to headerMessage
        }
        val trailerStatus = grpcWebTrailerFields(body)["grpc-status"]?.toIntOrNull()
        val trailerMessage = grpcWebTrailerFields(body)["grpc-message"]
        if (trailerStatus != null && trailerStatus != 0) {
            return trailerStatus to trailerMessage
        }
        return 0 to null
    }

    fun grpcWebFrame(payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size)
        frame[0] = 0
        frame[1] = ((payload.size ushr 24) and 0xff).toByte()
        frame[2] = ((payload.size ushr 16) and 0xff).toByte()
        frame[3] = ((payload.size ushr 8) and 0xff).toByte()
        frame[4] = (payload.size and 0xff).toByte()
        payload.copyInto(frame, 5)
        return frame
    }

    internal fun encodeTokens(tokens: List<SuperGrokResetToken>): ByteArray {
        val payload =
            tokens.fold(ByteArray(0)) { acc, token ->
                acc + lengthDelimited(FIELD_TOKENS, encodeToken(token))
            }
        return grpcWebFrame(payload)
    }

    private fun encodeToken(token: SuperGrokResetToken): ByteArray {
        var bytes = lengthDelimited(FIELD_TOKEN_ID, token.tokenId.encodeToByteArray())
        token.expiresAt?.let { expiresAt ->
            bytes += lengthDelimited(FIELD_VALIDITY_END, encodeTimestamp(expiresAt))
        }
        return bytes
    }

    private fun encodeTimestamp(instant: Instant): ByteArray {
        val seconds = instant.epochSeconds
        val nanos = instant.nanosecondsOfSecond
        var bytes = varintField(FIELD_TIMESTAMP_SECONDS, seconds)
        if (nanos != 0) {
            bytes += varintField(FIELD_TIMESTAMP_NANOS, nanos.toLong())
        }
        return bytes
    }

    private fun parseTokenList(payload: ByteArray): List<SuperGrokResetToken> {
        return readFields(payload).mapNotNull { field ->
            if (field.number != FIELD_TOKENS || field.bytes == null) return@mapNotNull null
            parseToken(field.bytes)
        }
    }

    private fun parseToken(payload: ByteArray): SuperGrokResetToken? {
        val fields = readFields(payload)
        val tokenId =
            fields
                .firstOrNull { it.number == FIELD_TOKEN_ID }
                ?.bytes
                ?.decodeToString()
                ?.trim()
                .orEmpty()
        if (tokenId.isEmpty()) return null
        val expiresAt =
            fields.firstOrNull { it.number == FIELD_VALIDITY_END }?.bytes?.let(::parseTimestamp)
        return SuperGrokResetToken(tokenId = tokenId, expiresAt = expiresAt)
    }

    private fun parseTimestamp(payload: ByteArray): Instant? {
        val fields = readFields(payload)
        val seconds =
            fields.firstOrNull { it.number == FIELD_TIMESTAMP_SECONDS }?.varint ?: return null
        val nanos = fields.firstOrNull { it.number == FIELD_TIMESTAMP_NANOS }?.varint ?: 0L
        return runCatching { Instant.fromEpochSeconds(seconds, nanos) }.getOrNull()
    }

    private fun grpcWebDataFrames(data: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var index = 0
        while (index + 5 <= data.size) {
            val flags = data[index].toInt() and 0xff
            val length = readFrameLength(data, index + 1)
            val start = index + 5
            val end = start + length
            if (length < 0 || end > data.size) return emptyList()
            if (flags and 0x80 == 0) {
                frames.add(data.copyOfRange(start, end))
            }
            index = end
        }
        return frames
    }

    private fun grpcWebTrailerFields(data: ByteArray): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        var index = 0
        while (index + 5 <= data.size) {
            val flags = data[index].toInt() and 0xff
            val length = readFrameLength(data, index + 1)
            val start = index + 5
            val end = start + length
            if (length < 0 || end > data.size) break
            if (flags and 0x80 != 0) {
                val text = data.copyOfRange(start, end).decodeToString()
                text.split('\n', '\r').forEach { line ->
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        val key = line.substring(0, separator).trim().lowercase()
                        val value = line.substring(separator + 1).trim()
                        if (key.isNotEmpty()) fields[key] = value
                    }
                }
            }
            index = end
        }
        return fields
    }

    private fun readFrameLength(data: ByteArray, index: Int): Int {
        return ((data[index].toInt() and 0xff) shl 24) or
            ((data[index + 1].toInt() and 0xff) shl 16) or
            ((data[index + 2].toInt() and 0xff) shl 8) or
            (data[index + 3].toInt() and 0xff)
    }

    private fun looksLikeProtobuf(data: ByteArray): Boolean {
        val first = data.firstOrNull()?.toInt()?.and(0xff) ?: return false
        val fieldNumber = first ushr 3
        val wireType = first and 0x07
        return fieldNumber > 0 &&
            (wireType == WIRE_VARINT || wireType == WIRE_LEN || wireType == 1 || wireType == 5)
    }

    private data class ProtoField(
        val number: Int,
        val varint: Long? = null,
        val bytes: ByteArray? = null,
    )

    private fun readFields(data: ByteArray): List<ProtoField> {
        val fields = mutableListOf<ProtoField>()
        var index = 0
        while (index < data.size) {
            val keyStart = index
            val key = readVarint(data, index) ?: break
            index = key.second
            val fieldNumber = (key.first ushr 3).toInt()
            when ((key.first and 0x07).toInt()) {
                WIRE_VARINT -> {
                    val value = readVarint(data, index) ?: break
                    index = value.second
                    fields.add(ProtoField(fieldNumber, varint = value.first))
                }
                WIRE_LEN -> {
                    val length = readVarint(data, index) ?: break
                    index = length.second
                    val end = index + length.first.toInt()
                    if (length.first < 0 || end > data.size) break
                    fields.add(ProtoField(fieldNumber, bytes = data.copyOfRange(index, end)))
                    index = end
                }
                1 -> {
                    if (index + 8 > data.size) break
                    index += 8
                }
                5 -> {
                    if (index + 4 > data.size) break
                    index += 4
                }
                else -> {
                    index = keyStart + 1
                }
            }
        }
        return fields
    }

    private fun lengthDelimited(fieldNumber: Int, payload: ByteArray): ByteArray {
        return tag(fieldNumber, WIRE_LEN) + writeVarint(payload.size.toLong()) + payload
    }

    private fun varintField(fieldNumber: Int, value: Long): ByteArray {
        return tag(fieldNumber, WIRE_VARINT) + writeVarint(value)
    }

    private fun tag(fieldNumber: Int, wireType: Int): ByteArray {
        return writeVarint(((fieldNumber shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long): ByteArray {
        var remaining = value
        val bytes = ArrayList<Byte>(10)
        while (true) {
            if ((remaining and 0x7fL.inv()) == 0L) {
                bytes.add(remaining.toByte())
                break
            }
            bytes.add(((remaining and 0x7fL) or 0x80L).toByte())
            remaining = remaining ushr 7
        }
        return bytes.toByteArray()
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var index = start
        while (index < data.size && shift < 64) {
            val byte = data[index].toInt() and 0xff
            index++
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value to index
            shift += 7
        }
        return null
    }

    private fun headerValue(headers: Map<String, List<String>>, name: String): String? {
        val expected = name.lowercase()
        return headers.entries
            .firstOrNull { it.key.equals(expected, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
