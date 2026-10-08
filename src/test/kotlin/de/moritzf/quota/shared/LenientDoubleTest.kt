package de.moritzf.quota.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LenientDoubleTest {
    @Test
    fun nullableAcceptsIntFloatDoubleAndNumericString() {
        assertEquals(0.0, decodeOrNull("0"))
        assertEquals(1.0, decodeOrNull("1"))
        assertEquals(15.0, decodeOrNull("15"))
        assertEquals(15.2, decodeOrNull("15.2"))
        assertEquals(15.0, decodeOrNull("15.0"))
        assertEquals(8.4, decodeOrNull("\"8.4\""))
        assertEquals(1.5e1, decodeOrNull("1.5e1"))
    }

    @Test
    fun nullableTreatsGarbageAsAbsent() {
        assertNull(decodeOrNull("null"))
        assertNull(decodeOrNull("\"broken\""))
        assertNull(decodeOrNull("true"))
        assertNull(decodeOrNull("{}"))
        assertNull(decodeOrNull("[]"))
    }

    @Test
    fun requiredAcceptsIntAndFloat() {
        assertEquals(0.0, decodeRequired("0"))
        assertEquals(15.2, decodeRequired("15.2"))
        assertEquals(100.0, decodeRequired("\"100\""))
    }

    @Test
    fun requiredRejectsGarbage() {
        val nope = assertFailsWith<SerializationException> { decodeRequired("\"nope\"") }
        assertTrue(nope.message!!.contains("nope"))
        assertFailsWith<SerializationException> { decodeRequired("null") }
        assertFailsWith<SerializationException> { decodeRequired("true") }
    }

    @Test
    fun serializesAsJsonNumber() {
        val encoded = JsonSupport.json.encodeToString(RequiredBox.serializer(), RequiredBox(15.2))
        val primitive =
            JsonSupport.json
                .parseToJsonElement(encoded)
                .jsonObject
                .getValue("usagePercent")
                .jsonPrimitive
        assertFalse(primitive.isString)
        assertEquals(15.2, primitive.double)
    }

    private fun decodeOrNull(raw: String): Double? {
        return JsonSupport.json
            .decodeFromString(NullableBox.serializer(), """{"usagePercent":$raw}""")
            .usagePercent
    }

    private fun decodeRequired(raw: String): Double {
        return JsonSupport.json
            .decodeFromString(RequiredBox.serializer(), """{"usagePercent":$raw}""")
            .usagePercent
    }

    @Serializable
    private data class NullableBox(
        @Serializable(with = LenientDoubleOrNullSerializer::class) val usagePercent: Double? = null
    )

    @Serializable
    private data class RequiredBox(
        @Serializable(with = LenientDoubleSerializer::class) val usagePercent: Double = 0.0
    )
}
