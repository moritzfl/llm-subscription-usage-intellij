package de.moritzf.quota.mistral

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class MistralMarkdownWriterTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun repeatedIdsAcrossPagesDocumentsAndRerunsNeverOverwriteImages() {
        val pages = listOf(
            MistralOcrPageDto("![first](img.png)", listOf(MistralOcrImageDto("img.png", "AQID"))),
            MistralOcrPageDto("![second](img.png)", listOf(MistralOcrImageDto("img.png", "BAUG"))),
        )
        val runs = listOf("first.md", "second.md", "first.md").map { name ->
            MistralMarkdownWriter(directory.resolve(name), true).use { writer ->
                writer.append(pages)
                writer.commit()
            }
        }
        assertEquals(6, runs.flatMap { it.imageFiles }.distinct().size)
        runs.forEach { run ->
            assertEquals(listOf<Byte>(1, 2, 3), Files.readAllBytes(Path.of(run.imageFiles[0])).toList())
            assertEquals(listOf<Byte>(4, 5, 6), Files.readAllBytes(Path.of(run.imageFiles[1])).toList())
        }
        assertNotEquals(Path.of(runs[0].imageFiles[0]).parent, Path.of(runs[2].imageFiles[0]).parent)
    }

    @Test
    fun rewritesInlineReferenceAndHtmlLinksWithPortableUnicodePaths() {
        val output = directory.resolve("Prüfung (neu).md")
        val text = """
            Prose img.png stays unchanged.
            ![caption](img.png "title")
            ![caption](<img.png>)
            ![caption][figure]
            [figure]: img.png
            <img src="img.png">
        """.trimIndent()
        val result = MistralMarkdownWriter(output, true).use { writer ->
            writer.append(listOf(MistralOcrPageDto(text, listOf(MistralOcrImageDto("img.png", "AQID")))))
            writer.commit()
        }
        val image = Path.of(result.imageFiles.single())
        val markdown = Files.readString(output)
        assertTrue(markdown.startsWith("Prose img.png stays unchanged."))
        val link = Regex("!\\[caption]\\((\\S+) ").find(markdown)!!.groupValues[1]
        assertTrue(link.contains("%C3%BC"))
        assertTrue(link.contains("%28neu%29"))
        assertEquals(image, directory.resolve(URI(link).path))
        assertTrue(markdown.contains("![caption](<$link>)"))
        assertTrue(markdown.contains("[figure]: $link"))
        assertTrue(markdown.contains("<img src=\"$link\">"))
    }

    @Test
    fun badImageDoesNotReplaceExistingMarkdownOrLeaveArtifacts() {
        val output = directory.resolve("existing.md")
        Files.writeString(output, "Existing")
        assertFailsWith<IllegalArgumentException> {
            MistralMarkdownWriter(output, true).use { writer ->
                writer.append(listOf(MistralOcrPageDto("partial", listOf(MistralOcrImageDto("ok.png", "AQID")))))
                writer.append(listOf(MistralOcrPageDto("broken", listOf(MistralOcrImageDto("bad.png", "%%%")))))
                writer.commit()
            }
        }
        assertEquals("Existing", Files.readString(output))
        Files.list(directory).use { assertEquals(listOf(output), it.toList()) }
    }

    @Test
    fun imagesDisabledDoesNotCreateImageFolder() {
        val output = directory.resolve("doc.md")
        val result = MistralMarkdownWriter(output, false).use { writer ->
            writer.append(listOf(MistralOcrPageDto("text", listOf(MistralOcrImageDto("img.png", "AQID")))))
            writer.commit()
        }
        assertTrue(result.imageFiles.isEmpty())
        Files.list(directory).use { assertEquals(listOf(output), it.toList()) }
        assertFalse(Files.readString(output).contains("images"))
    }
}
