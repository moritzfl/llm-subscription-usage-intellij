package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.mcp.VisionProvider
import de.moritzf.quota.shared.JsonSupport
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DocumentAltTextOperationsTest {
    @Test
    fun addsAltTextReportAndKeepsProviderFields() {
        val dir = Files.createTempDirectory("alt-text-op")
        Files.write(dir.resolve("a.png"), byteArrayOf(1))
        val output = dir.resolve("doc.md")
        Files.writeString(output, "![img-0](a.png)")
        val result =
            """{"output_file":${JsonSupport.json.encodeToString(output.toString())},"image_files":["a.png"],"warnings":["kept"],"custom":1}"""

        val json =
            JsonSupport.json.parseToJsonElement(
                withAltText(result, VisionProvider.MISTRAL) { provider, _ ->
                    assertEquals(VisionProvider.MISTRAL, provider)
                    "A diagram."
                },
            ) as JsonObject

        assertEquals("![A diagram.](a.png)", Files.readString(output))
        assertEquals("1", json.getValue("custom").jsonPrimitive.content)
        assertEquals("kept", json.getValue("warnings").jsonArray.single().jsonPrimitive.content)
        val altText = json.getValue("alt_text").jsonObject
        assertEquals("MISTRAL", altText.getValue("provider").jsonPrimitive.content)
        assertEquals("1", altText.getValue("described").jsonPrimitive.content)
    }

    @Test
    fun leavesErrorsAndTextResultsUsable() {
        val error = """{"error":"OCR failed."}"""
        assertEquals(error, withAltText(error, VisionProvider.ZAI) { _, _ -> error("unused") })

        val text =
            withAltText("""{"markdown":"![x](a.png)"}""", VisionProvider.ZAI) { _, _ ->
                error("unused")
            }
        assertTrue(text.contains("Alt text needs a Markdown output file."))
        assertTrue(text.contains("\"markdown\""))
    }
}
