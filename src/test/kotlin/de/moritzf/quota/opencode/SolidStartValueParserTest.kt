package de.moritzf.quota.opencode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SolidStartValueParserTest {
    @Test
    fun parsesObjectWithReferencesAndArrays() {
        val input = """${'$'}R[0]={flag:!0,items:${'$'}R[1]=["a",2,!1],child:${'$'}R[2]={name:"ok"}}"""

        val result = SolidStartValueParser(input, 0).parseValue()

        val obj = assertIs<JsonObject>(result)
        assertEquals(JsonPrimitive(true), obj["flag"])
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("2"), JsonPrimitive(false))), obj["items"])
        assertEquals(JsonObject(mapOf("name" to JsonPrimitive("ok"))), obj["child"])
    }

    @Test
    fun parsesStringsWithEscapesAndBraces() {
        val input = """{text:"line\n{still text}",quote:"say \"hi\"",unicode:"\u0041"}"""

        val result = SolidStartValueParser(input, 0).parseValue()

        val obj = assertIs<JsonObject>(result)
        assertEquals("line\n{still text}", obj.getValue("text").jsonPrimitive.content)
        assertEquals("say \"hi\"", obj.getValue("quote").jsonPrimitive.content)
        assertEquals("A", obj.getValue("unicode").jsonPrimitive.content)
    }

    @Test
    fun parsesNullAndNumericLiterals() {
        val input = """[${'$'}R[1]=null,-12,3.5,6e2]"""

        val result = SolidStartValueParser(input, 0).parseValue()

        val array = assertIs<JsonArray>(result)
        assertEquals(JsonNull, array[0])
        assertEquals(JsonPrimitive("-12"), array[1])
        assertEquals(JsonPrimitive("3.5"), array[2])
        assertEquals(JsonPrimitive("6e2"), array[3])
    }

    @Test
    fun failsOnReferenceBeforeAssignment() {
        val exception = assertFailsWith<OpenCodeQuotaException> {
            SolidStartValueParser("""{child:${'$'}R[2]}""", 0).parseValue()
        }

        assertTrue(exception.message!!.contains("used before assignment"))
    }

    @Test
    fun failsOnStringReferenceIndex() {
        val exception = assertFailsWith<OpenCodeQuotaException> {
            SolidStartValueParser("""${'$'}R["server-fn:1"]""", 0).parseValue()
        }

        assertTrue(exception.message!!.contains("String-based references"))
    }
}
