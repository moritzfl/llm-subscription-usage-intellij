package de.moritzf.quota.shared

import de.moritzf.quota.openai.proxy.pdf.PdfFigureRegion
import de.moritzf.quota.openai.proxy.pdf.PdfFigureRenderer
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW

internal class ProviderDocumentImage(val bytes: ByteArray, val extension: String)

/** Owns a unique image folder. Uncommitted files are removed with the surrounding Markdown transaction. */
internal class DocumentImageWriter(
    private val outputFile: Path,
    private val options: DocumentImageOptions,
    private val originalPdf: () -> PdfFigureRenderer? = { null },
) : AutoCloseable {
    private var directory: Path? = null
    private var renderer: PdfFigureRenderer? = null
    private var sourceOpened = false
    private var sourceError: String? = null
    private var committed = false
    private val files = mutableListOf<Path>()
    val imageFiles: List<String> get() = files.map(Path::toString)
    val warnings = mutableListOf<String>()

    fun write(page: Int, ordinal: Int, region: PdfFigureRegion?, provider: () -> ProviderDocumentImage?): String? {
        val label = "Page $page, figure $ordinal"
        if (options.format != DocumentImageFormat.PROVIDER) {
            val source = if (region != null) renderer() else null
            val reason = when {
                region == null -> "OCR returned no usable figure coordinates"
                source == null -> sourceError ?: "original PDF unavailable (image input or no PDF source)"
                else -> null
            }
            if (source != null && region != null) {
                if (options.format == DocumentImageFormat.SVG) {
                    val svg = target(page, ordinal, "svg")
                    try {
                        source.renderSvg(region, svg, options.paddingPoints)
                        return retain(svg)
                    } catch (exception: Exception) {
                        rethrowCancellation(exception)
                        Files.deleteIfExists(svg)
                        warnings += "$label: SVG export unavailable (${exception.message}); trying PNG at ${options.dpi} DPI."
                    }
                }
                val png = target(page, ordinal, "png")
                try {
                    source.renderPng(region, png, options.dpi, options.paddingPoints)
                    return retain(png)
                } catch (exception: Exception) {
                    rethrowCancellation(exception)
                    Files.deleteIfExists(png)
                    warnings += "$label: PDF image export failed (${exception.message}); using provider image."
                }
            } else warnings += "$label: $reason; using provider image."
        }
        val image = provider()
        if (image == null || image.bytes.isEmpty()) {
            warnings += "$label: provider supplied no image; image omitted."
            return null
        }
        val extension = image.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "png"
        val target = target(page, ordinal, extension)
        Files.write(target, image.bytes, CREATE_NEW)
        return retain(target)
    }

    private fun renderer(): PdfFigureRenderer? {
        if (!sourceOpened) {
            sourceOpened = true
            try {
                renderer = originalPdf()
            } catch (exception: Exception) {
                rethrowCancellation(exception)
                sourceError = "original PDF could not be opened (${exception.message})"
            }
        }
        return renderer
    }

    private fun target(page: Int, ordinal: Int, extension: String): Path {
        val folder = directory ?: run {
            val parent = outputFile.toAbsolutePath().parent
            Files.createDirectories(parent)
            Files.createTempDirectory(
                parent,
                "${outputFile.fileName.toString().substringBeforeLast('.').take(60)}-images-"
            )
                .also { directory = it }
        }
        return folder.resolve("page-$page-image-$ordinal.$extension")
    }

    private fun retain(path: Path): String {
        files.add(path)
        return URI(null, null, "${path.parent.fileName}/${path.fileName}", null).toASCIIString()
            .replace("(", "%28").replace(")", "%29")
    }

    fun commit() {
        committed = true
    }

    override fun close() {
        try {
            renderer?.close()
        } finally {
            if (!committed) {
                directory?.let { folder ->
                    Files.list(folder).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
                    Files.deleteIfExists(folder)
                }
            }
        }
    }
}

/** Never turn IDE/coroutine cancellation into a provider-image fallback. */
internal fun rethrowCancellation(exception: Exception) {
    if (exception is java.util.concurrent.CancellationException || exception is InterruptedException ||
        exception is com.intellij.openapi.diagnostic.ControlFlowException
    ) throw exception
}
