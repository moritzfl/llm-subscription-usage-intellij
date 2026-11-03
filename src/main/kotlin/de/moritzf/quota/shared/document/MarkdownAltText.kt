package de.moritzf.quota.shared.document

import de.moritzf.quota.shared.DocumentConversionProgress
import de.moritzf.quota.shared.DocumentMarkdown
import de.moritzf.quota.shared.SvgRasterizer
import de.moritzf.quota.shared.rethrowCancellation
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * Replaces the alt text of local Markdown images with descriptions from a vision model. Remote and
 * data URLs stay untouched; SVG figures are rasterized first because vision APIs take bitmaps.
 */
internal object MarkdownAltText {
    const val PROMPT =
        "Write alt text for this figure, which was extracted from a document. " +
            "In one to three sentences, describe what it shows, including key labels, values, or trends. " +
            "Reply with the alt text only, without quotes or a prefix."

    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val SVG_DPI = 150
    private val IMAGE = Regex("""!\[([^\]\r\n]*)]\(\s*(<[^>\r\n]+>|[^)\s]+)([^)\r\n]*)\)""")

    fun apply(
        markdownFile: Path,
        progress: DocumentConversionProgress = DocumentConversionProgress.NONE,
        describe: (Path) -> String,
    ): AltTextReport {
        val markdown = Files.readString(markdownFile)
        val base = markdownFile.toAbsolutePath().parent
        val images =
            IMAGE.findAll(markdown)
                .mapNotNull { localImage(base, it.groupValues[2]) }
                .distinct()
                .toList()
        val descriptions = mutableMapOf<Path, String>()
        val warnings = mutableListOf<String>()
        var consecutiveFailures = 0
        for ((index, image) in images.withIndex()) {
            progress.update(index, images.size, "Describing image ${index + 1} of ${images.size}")
            try {
                descriptions[image] = clean(withRaster(image, describe))
                consecutiveFailures = 0
            } catch (exception: Exception) {
                rethrowCancellation(exception)
                warnings +=
                    "Alt text failed for ${base.relativize(image)}: " +
                        (exception.message ?: exception.javaClass.simpleName)
                if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && index < images.lastIndex) {
                    warnings +=
                        "Alt text skipped for ${images.lastIndex - index} more images after repeated failures."
                    break
                }
            }
        }
        progress.update(images.size, images.size, "Image descriptions done")
        val described = descriptions.filterValues(String::isNotEmpty)
        if (described.isNotEmpty()) {
            val updated =
                IMAGE.replace(markdown) { match ->
                    val alt =
                        localImage(base, match.groupValues[2])?.let(described::get)
                            ?: return@replace match.value
                    "![$alt](${match.groupValues[2]}${match.groupValues[3]})"
                }
            DocumentMarkdown.writeAtomically(markdownFile, updated)
        }
        return AltTextReport(images.size, described.size, warnings)
    }

    private fun localImage(base: Path, rawTarget: String): Path? {
        val target = rawTarget.removePrefix("<").removeSuffix(">").trim()
        if (target.isEmpty() || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(target)) {
            return null
        }
        val candidates =
            listOf(
                target,
                runCatching { URLDecoder.decode(target, StandardCharsets.UTF_8) }.getOrNull(),
            )
        return candidates.firstNotNullOfOrNull { candidate ->
            candidate
                ?.let {
                    try {
                        base.resolve(it).normalize()
                    } catch (_: InvalidPathException) {
                        null
                    }
                }
                ?.takeIf(Files::isRegularFile)
        }
    }

    private fun withRaster(image: Path, describe: (Path) -> String): String {
        if (!image.fileName.toString().endsWith(".svg", ignoreCase = true)) return describe(image)
        val raster = Files.createTempFile("alt-text-", ".png")
        try {
            SvgRasterizer.toPng(image, raster, SVG_DPI)
            return describe(raster)
        } finally {
            Files.deleteIfExists(raster)
        }
    }

    internal fun clean(answer: String): String =
        DocumentMarkdown.unwrap(answer)
            .replace(Regex("""^\s*alt(?:\s|-)?text\s*:\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .removeSurrounding("\"")
            .trim()
            .replace("\\", "\\\\")
            .replace("[", "\\[")
            .replace("]", "\\]")
}

@Serializable
internal data class AltTextReport(
    val images: Int,
    val described: Int,
    val warnings: List<String> = emptyList(),
)
