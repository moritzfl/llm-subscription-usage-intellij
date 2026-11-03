package de.moritzf.quota.shared.document

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownAltTextTest {
    @Test
    fun replacesLocalImageAltTextOncePerFile() {
        val dir = Files.createTempDirectory("alt-text")
        Files.createDirectories(dir.resolve("figures"))
        Files.write(dir.resolve("figures/a b.png"), byteArrayOf(1))
        Files.write(dir.resolve("figures/c.jpeg"), byteArrayOf(2))
        val markdown = dir.resolve("doc.md")
        Files.writeString(
            markdown,
            """
            # Title
            ![img-0.jpeg](figures/a%20b.png)
            ![](<figures/c.jpeg> "Caption")
            ![again](figures/a%20b.png)
            ![remote](https://example.com/x.png)
            ![missing](figures/none.png)
            """
                .trimIndent(),
        )
        val seen = mutableListOf<Path>()

        val report =
            MarkdownAltText.apply(markdown) { image ->
                seen.add(image)
                "Alt text: \"Bar chart [2024]\n  of sales.\""
            }

        assertEquals(2, seen.size)
        assertEquals(2, report.images)
        assertEquals(2, report.described)
        assertEquals(
            """
            # Title
            ![Bar chart \[2024\] of sales.](figures/a%20b.png)
            ![Bar chart \[2024\] of sales.](<figures/c.jpeg> "Caption")
            ![Bar chart \[2024\] of sales.](figures/a%20b.png)
            ![remote](https://example.com/x.png)
            ![missing](figures/none.png)
            """
                .trimIndent(),
            Files.readString(markdown),
        )
    }

    @Test
    fun keepsOriginalAltTextAndStopsAfterRepeatedFailures() {
        val dir = Files.createTempDirectory("alt-text-fail")
        val lines =
            (1..5).joinToString("\n") { index ->
                Files.write(dir.resolve("$index.png"), byteArrayOf(index.toByte()))
                "![original $index]($index.png)"
            }
        val markdown = dir.resolve("doc.md")
        Files.writeString(markdown, lines)
        var calls = 0

        val report =
            MarkdownAltText.apply(markdown) {
                calls++
                error("Vision is off.")
            }

        assertEquals(3, calls)
        assertEquals(0, report.described)
        assertEquals(lines, Files.readString(markdown))
        assertTrue(report.warnings.first().contains("Vision is off."))
        assertTrue(report.warnings.last().contains("2 more images"))
    }

    @Test
    fun rasterizesSvgBeforeDescribing() {
        val dir = Files.createTempDirectory("alt-text-svg")
        Files.writeString(
            dir.resolve("figure.svg"),
            """<svg xmlns="http://www.w3.org/2000/svg" width="10" height="10"><rect width="10" height="10"/></svg>""",
        )
        val markdown = dir.resolve("doc.md")
        Files.writeString(markdown, "![figure](figure.svg)")
        var described: Path? = null

        MarkdownAltText.apply(markdown) { image ->
            described = image
            assertTrue(Files.size(image) > 0)
            "A black square."
        }

        assertTrue(described.toString().endsWith(".png"))
        assertTrue(Files.notExists(described!!))
        assertEquals("![A black square.](figure.svg)", Files.readString(markdown))
    }
}
