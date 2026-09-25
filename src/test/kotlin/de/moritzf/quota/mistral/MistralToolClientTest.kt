package de.moritzf.quota.mistral

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MistralToolClientTest {
    @Test
    fun conversationRequestUsesWebSearchTool() {
        val json = MistralWebSearchClient.conversationRequestJson("hello", "mistral-small-latest", premium = false)
        assertTrue(json.contains("\"web_search\""))
        assertTrue(json.contains("hello"))
    }

    @Test
    fun conversationRequestCanUsePremiumSearch() {
        val json = MistralWebSearchClient.conversationRequestJson("hello", "mistral-small-latest", premium = true)
        assertTrue(json.contains("\"web_search_premium\""))
    }

    @Test
    fun imageOutputDefaultsToUniqueProjectFile() {
        val dir = Path.of("/tmp/project").toAbsolutePath().normalize()
        val default = MistralImageClient.resolveOutput(null, dir)
        assertEquals(dir, default?.parent)
        assertTrue(default!!.fileName.toString().matches(Regex("image-[0-9a-f-]{36}\\.png")))
        assertEquals(dir.resolve("out/hi.png"), MistralImageClient.resolveOutput("out/hi.png", dir))
        assertEquals(null, MistralImageClient.resolveOutput(null, null))
    }

    @Test
    fun firstToolFileIdReadsNestedConversationOutput() {
        val body = """
            {
              "outputs": [
                {"type": "tool.execution", "name": "image_generation"},
                {
                  "type": "message.output",
                  "content": [
                    {"type": "text", "text": "here"},
                    {"type": "tool_file", "file_id": "file-123", "file_type": "png"}
                  ]
                }
              ]
            }
        """.trimIndent()
        assertEquals("file-123", MistralImageClient.firstToolFileId(body))
    }

    @Test
    fun transcriptionJsonUsesFileUrlAndOptionalLanguage() {
        val json = MistralAudioClient.transcriptionJson("voxtral-mini-latest", "https://example.com/a.mp3", "en", true)
        assertTrue("file_url" in json && "https://example.com/a.mp3" in json)
        assertTrue("\"language\"" in json && "en" in json)
        assertTrue("\"diarize\"" in json && "true" in json)
    }

    @Test
    fun firstPresetVoiceIdReadsListItems() {
        val body = """{"items":[{"id":"voice-1","name":"Ada"},{"id":"voice-2"}]}"""
        assertEquals("voice-1", MistralAudioClient.firstPresetVoiceId(body))
        assertEquals(null, MistralAudioClient.firstPresetVoiceId("{}"))
    }

    @Test
    fun speechOutputDefaultsToProjectSpeechFile() {
        val dir = Path.of("/tmp/project").toAbsolutePath().normalize()
        assertEquals(dir.resolve("out/hi.mp3"), MistralAudioClient.resolveOutput("out/hi.mp3", dir, "mp3"))
        val default = MistralAudioClient.resolveOutput(null, dir, "wav")
        assertEquals(dir, default?.parent)
        assertTrue(default!!.fileName.toString().matches(Regex("speech-[0-9a-f-]{36}\\.wav")))
        assertEquals(null, MistralAudioClient.resolveOutput(null, null, "mp3"))
    }

    @Test
    fun writeMarkdownPersistsImagesInIsolatedFolderAndRewritesLinks() {
        val dir = Files.createTempDirectory("mistral-ocr")
        val markdownFile = dir.resolve("doc.md")
        val png = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val body = """
            {
              "pages": [
                {
                  "markdown": "Hello ![img-0.jpeg](img-0.jpeg)",
                  "images": [{"id": "img-0.jpeg", "image_base64": "$png"}]
                }
              ]
            }
        """.trimIndent()

        val result = MistralOcrClient.writeMarkdown(body, markdownFile, includeImages = true)

        assertEquals(markdownFile.toString(), result.outputFile)
        assertEquals(1, result.pages)
        val image = Path.of(result.imageFiles.single())
        assertEquals(dir, image.parent.parent)
        assertTrue(image.parent.fileName.toString().startsWith("doc-images-"))
        assertEquals("Hello ![img-0.jpeg](${image.parent.fileName}/${image.fileName})", Files.readString(markdownFile))
        assertTrue(Files.size(image) > 0)
    }

    @Test
    fun writeMarkdownAcceptsDataUriAndIgnoresPathTraversalIds() {
        val dir = Files.createTempDirectory("mistral-ocr-uri")
        val markdownFile = dir.resolve("doc.md")
        val png = Base64.getEncoder().encodeToString(byteArrayOf(9, 8, 7))
        val body = """
            {
              "pages": [
                {
                  "markdown": "A ![img-0.jpeg](img-0.jpeg)",
                  "images": [
                    {"id": "../img-0.jpeg", "image_base64": "data:image/jpeg;base64,$png"},
                    {"id": "..", "image_base64": "$png"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = MistralOcrClient.writeMarkdown(body, markdownFile, includeImages = true)

        val image = Path.of(result.imageFiles.single())
        assertEquals(dir, image.parent.parent)
        assertEquals(byteArrayOf(9, 8, 7).toList(), Files.readAllBytes(image).toList())
        assertEquals("A ![img-0.jpeg](${image.parent.fileName}/${image.fileName})", Files.readString(markdownFile))
    }

    @Test
    fun imageFileNameKeepsApiBasename() {
        assertEquals("img-0.jpeg", MistralOcrClient.imageFileName("img-0.jpeg"))
        assertEquals("img-0.jpeg", MistralOcrClient.imageFileName("../img-0.jpeg"))
        assertEquals(null, MistralOcrClient.imageFileName(".."))
        assertEquals(null, MistralOcrClient.imageFileName("  "))
    }

    @Test
    fun defaultMarkdownOutputSitsBesideLocalFile() {
        val pdf = Path.of("/tmp/ticket.pdf")
        assertEquals(Path.of("/tmp/ticket.md"), MistralOcrClient.defaultMarkdownOutput(pdf))
        assertEquals(null, MistralOcrClient.defaultMarkdownOutput(null))
    }

    @Test
    fun ocrRequestDisablesBlockPayload() {
        val json = MistralOcrClient.ocrRequestJson(
            "mistral-ocr-latest",
            MistralOcrDocumentDto(type = "file", fileId = "file-1"),
            includeImageBase64 = true,
        )
        assertTrue("\"include_blocks\": false" in json)
        assertTrue("\"include_image_base64\": true" in json)
        assertTrue("\"file_id\": \"file-1\"" in json)
        assertTrue("document_url" !in json)
    }

    @Test
    fun writeMarkdownAcceptsNullImages() {
        val dir = Files.createTempDirectory("mistral-ocr-null-images")
        val markdownFile = dir.resolve("doc.md")
        val body = """{"pages":[{"markdown":"Hi","images":null}]}"""
        val result = MistralOcrClient.writeMarkdown(body, markdownFile, includeImages = true)
        assertEquals(1, result.pages)
        assertEquals(emptyList(), result.imageFiles)
        assertEquals("Hi", Files.readString(markdownFile))
    }

    @Test
    fun mistralErrorDetailReadsMessageAndNestedError() {
        assertEquals("bad file", MistralOcrClient.mistralErrorDetail("""{"message":"bad file"}"""))
        assertEquals("nope", MistralOcrClient.mistralErrorDetail("""{"error":{"message":"nope"}}"""))
        assertEquals("invalid", MistralOcrClient.mistralErrorDetail("""{"detail":[{"msg":"invalid"}]}"""))
        assertEquals(null, MistralOcrClient.mistralErrorDetail("not-json"))
    }
}
