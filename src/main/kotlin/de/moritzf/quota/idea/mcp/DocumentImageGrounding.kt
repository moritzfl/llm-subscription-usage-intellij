package de.moritzf.quota.idea.mcp

import java.nio.file.Path

/**
 * Parses model-emitted grounding comments of the form `<!-- img page=N x0 y0 x1 y1 -->` that
 * immediately follow the `![alt](image-pN-M.png)` placeholder they describe.
 *
 * Codex vision has no native bounding-box output, so regions are model estimates normalized to
 * [0,1] page coordinates (top-left origin). Imprecise boxes degrade to slightly off crops, never
 * whole-page renders.
 */
internal object DocumentImageGrounding {

    /** System/instructions text directing the vision model to emit grounded image placeholders. */
    const val INSTRUCTIONS =
        "Convert the attached document to markdown. Preserve headings, lists, tables, and code. " +
            "For every embedded image, chart, figure, diagram, or photograph you see, emit a markdown image at the exact position it appears in the source using this format: " +
            "![<concise description of the image content, including any visible text, axes, data values, colors, or labels>](image-p<page>-<index>.png) " +
            "where <page> is the 1-based source page number and <index> is the 1-based position of that image on that page. " +
            "Immediately after that image line, on its own line, emit a grounding comment with the image's bounding box as normalized page coordinates (0.0 to 1.0, origin at the top-left of the page): " +
            "<!-- img page=<page> <x0> <y0> <x1> <y1> --> " +
            "Estimate the box tightly around the figure, not the whole page. Never write [image], (image), ![image], or other empty placeholders. " +
            "Return only markdown (the grounding comments are part of it; do not omit them)."

    /**
     * User-turn prompt that restates the grounding requirement for models without an instructions
     * field.
     */
    const val PROMPT =
        "Convert this document to markdown. For each embedded image or figure, emit " +
            "![<detailed description>](image-p<page>-<index>.png) at its position, followed by a grounding line " +
            "<!-- img page=<page> <x0> <y0> <x1> <y1> --> with the figure's normalized page bounding box. " +
            "Return only the markdown."

    private val GROUNDING_LINE =
        Regex(
            """<!--\s*img\s+page=(\d+)\s+([0-9]*\.?[0-9]+)\s+([0-9]*\.?[0-9]+)\s+([0-9]*\.?[0-9]+)\s+([0-9]*\.?[0-9]+)\s*-->"""
        )

    /**
     * Matches any `img` grounding comment, including malformed ones, so they never leak into
     * output.
     */
    private val ANY_GROUNDING_COMMENT = Regex("""<!--\s*img\s[^>]*-->""")

    private val PLACEHOLDER_IMAGE = Regex("""!\[([^\]]*)]\(image-p(\d+)-(\d+)\.png\)""")

    data class Region(
        val page: Int,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val index: Int,
    ) {
        val fallbackFileName: String
            get() = "image-p$page-$index.png"
    }

    data class ParseResult(
        /** Markdown with all grounding comments removed, safe to persist and display. */
        val markdown: String,
        val regions: List<Region>,
    )

    fun parse(markdown: String): ParseResult {
        val regions = mutableListOf<Region>()
        GROUNDING_LINE.findAll(markdown).forEach { match ->
            val page = match.groupValues[1].toIntOrNull() ?: return@forEach
            val coords = match.groupValues.drop(2).take(4).mapNotNull { it.toFloatOrNull() }
            if (coords.size != 4) return@forEach
            val index = regionsOnPage(regions, page) + 1
            regions += Region(page, coords[0], coords[1], coords[2], coords[3], index)
        }
        val cleaned =
            markdown
                .lines()
                .filterNot { ANY_GROUNDING_COMMENT.containsMatchIn(it) }
                .joinToString("\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim() + "\n"
        return ParseResult(cleaned, regions)
    }

    private fun regionsOnPage(regions: List<Region>, page: Int): Int = regions.count {
        it.page == page
    }

    /**
     * Strips grounding comments from [markdown] and, when [sourcePdf] is a readable local PDF and
     * extraction is enabled, renders each grounded region to [outputDir]. Returns the clean
     * markdown and the image file paths actually written.
     */
    fun apply(
        markdown: String,
        sourcePdf: Path?,
        outputDir: Path?,
        includeImages: Boolean,
        pageOffset: Int = 0,
    ): Applied {
        val parsed =
            remapPageOffset(
                parse(de.moritzf.quota.shared.DocumentMarkdown.unwrap(markdown)),
                pageOffset,
            )
        val imageFiles =
            if (
                includeImages &&
                    sourcePdf != null &&
                    outputDir != null &&
                    parsed.regions.isNotEmpty()
            ) {
                extractImages(sourcePdf, parsed.regions, outputDir)
            } else {
                emptyList()
            }
        val writtenNames = imageFiles.map { it.fileName.toString() }.toSet()
        return Applied(
            rewriteMissingImageLinks(parsed.markdown, writtenNames),
            imageFiles.map { it.toString() },
        )
    }

    internal fun remapPageOffset(parsed: ParseResult, pageOffset: Int): ParseResult {
        if (pageOffset == 0) return parsed
        val regions = parsed.regions.map { it.copy(page = it.page + pageOffset) }
        val markdown =
            PLACEHOLDER_IMAGE.replace(parsed.markdown) { match ->
                val alt = match.groupValues[1]
                val page = match.groupValues[2].toInt() + pageOffset
                val index = match.groupValues[3]
                "![$alt](image-p$page-$index.png)"
            }
        return ParseResult(markdown, regions)
    }

    /**
     * Turns `![alt](image-pN-M.png)` into a figure caption when that file was not written, so
     * markdown never points at a missing image.
     */
    internal fun rewriteMissingImageLinks(markdown: String, writtenFileNames: Set<String>): String {
        return PLACEHOLDER_IMAGE.replace(markdown) { match ->
            val alt = match.groupValues[1].trim()
            val fileName = "image-p${match.groupValues[2]}-${match.groupValues[3]}.png"
            if (fileName in writtenFileNames) {
                match.value
            } else {
                if (alt.isEmpty()) "" else "**Figure.** $alt"
            }
        }
    }

    data class Applied(val markdown: String, val imageFiles: List<String>)

    /**
     * Renders each region from [pdfFile] to [outputDir]/`image-pN-M.png`. Returns the paths
     * actually written. Never renders whole pages; failures for individual regions are skipped so
     * one bad box does not drop the rest.
     */
    fun extractImages(
        pdfFile: Path,
        regions: List<Region>,
        outputDir: Path,
        extractor: de.moritzf.quota.openai.proxy.pdf.PdfRegionImageExtractor? =
            de.moritzf.quota.openai.proxy.pdf.PdfRegionImageExtractor.open(pdfFile),
    ): List<Path> {
        if (regions.isEmpty()) return emptyList()
        val ex = extractor ?: return emptyList()
        return ex.use { renderer ->
            regions.mapNotNull { region ->
                runCatching {
                    val target = outputDir.resolve(region.fallbackFileName)
                    val pageRegion =
                        de.moritzf.quota.openai.proxy.pdf.PdfRegionImageExtractor.PageRegion(
                            region.page,
                            region.x0,
                            region.y0,
                            region.x1,
                            region.y1,
                        )
                    if (renderer.renderRegion(pageRegion, target)) target else null
                }
                    .getOrNull()
            }
        }
    }
}
