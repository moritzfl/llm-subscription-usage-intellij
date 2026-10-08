package de.moritzf.quota.shared

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class VisionChatTest {
    @Test
    fun chatRequestBuildsImageAndTextParts() {
        val image = VisionChat.chatImageContent("https://example.com/a.png", null)!!
        val json =
            JsonSupport.json
                .parseToJsonElement(
                    VisionChat.chatRequestJson("pixtral-large-latest", image, "What is this?")
                )
                .jsonObject

        assertEquals("pixtral-large-latest", json["model"]!!.jsonPrimitive.content)
        val message = json["messages"]!!.jsonArray[0].jsonObject
        assertEquals("user", message["role"]!!.jsonPrimitive.content)
        val content = message["content"]!!.jsonArray
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "https://example.com/a.png",
            content[0].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertEquals("What is this?", content[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun localFileBecomesImageDataUrl() {
        val dir = Files.createTempDirectory("vision-chat")
        val png = dir.resolve("pic.png")
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        Files.write(png, pngBytes)

        val image = assertNotNull(VisionChat.chatImageContent(null, png))
        val url = image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("data:image/png;base64,"))

        val pdf = dir.resolve("doc.pdf")
        Files.write(pdf, "%PDF-1.4".toByteArray())
        assertNull(VisionChat.chatImageContent(null, pdf))
    }

    @Test
    fun chatAnswerReadsStringAndTextParts() {
        assertEquals(
            "a red box",
            VisionChat.chatAnswer("""{"choices":[{"message":{"content":"a red box"}}]}"""),
        )
        assertEquals(
            "part one part two",
            VisionChat.chatAnswer(
                """{"choices":[{"message":{"content":[{"type":"text","text":"part one "},{"type":"text","text":"part two"}]}}]}"""
            ),
        )
        assertNull(VisionChat.chatAnswer("""{"choices":[{"message":{"content":""}}]}"""))
        assertNull(VisionChat.chatAnswer("not json"))
    }
}
