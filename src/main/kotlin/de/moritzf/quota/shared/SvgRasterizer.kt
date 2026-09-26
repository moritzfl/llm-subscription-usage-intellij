package de.moritzf.quota.shared

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.AtomicMoveNotSupportedException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.apache.batik.transcoder.SVGAbstractTranscoder
import org.apache.batik.transcoder.TranscoderInput
import org.apache.batik.transcoder.TranscoderOutput
import org.apache.batik.transcoder.image.PNGTranscoder

/** Local SVG to PNG. Figure exports use PDF points as SVG user units, so DPI is points × dpi / 72. */
internal object SvgRasterizer {
    private val WIDTH = Regex("""<svg\b[^>]*\swidth="([0-9]+(?:\.[0-9]+)?)"""", RegexOption.IGNORE_CASE)
    private val HEIGHT = Regex("""<svg\b[^>]*\sheight="([0-9]+(?:\.[0-9]+)?)"""", RegexOption.IGNORE_CASE)

    fun toPng(source: Path, output: Path?, dpi: Int = 300): String {
        require(dpi in 72..600) { "Image export DPI must be between 72 and 600." }
        if (!Files.isRegularFile(source)) throw IOException("Select a readable SVG file.")
        val destination = output ?: defaultOutput(source)
        if (destination.toAbsolutePath().normalize() == source.toAbsolutePath().normalize()) {
            throw IOException("PNG output must be a different file from the SVG.")
        }
        val xml = Files.readString(source)
        val transcoder = PNGTranscoder()
        transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_ALLOW_EXTERNAL_RESOURCES, false)
        pixelSize(xml, dpi)?.let { (width, height) ->
            transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_WIDTH, width)
            transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_HEIGHT, height)
        }
        val parent = destination.toAbsolutePath().parent
        if (parent != null) Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".svg-raster-", ".png")
        try {
            Files.newInputStream(source).use { input ->
                Files.newOutputStream(temporary).use { png ->
                    transcoder.transcode(TranscoderInput(input), TranscoderOutput(png))
                }
            }
            try {
                Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, REPLACE_EXISTING)
            }
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            if (exception is IOException) throw exception
            throw IOException(exception.message ?: "SVG rasterization failed.", exception)
        }
        return JsonSupport.json.encodeToString(SvgRasterResult(destination.toString(), dpi))
    }

    fun defaultOutput(source: Path): Path {
        val name = source.fileName.toString()
        val stem = name.substringBeforeLast('.', name).ifBlank { name }
        return source.resolveSibling("$stem.png")
    }

    private fun pixelSize(xml: String, dpi: Int): Pair<Float, Float>? {
        val width = WIDTH.find(xml)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        val height = HEIGHT.find(xml)?.groupValues?.get(1)?.toFloatOrNull() ?: return null
        if (width <= 0f || height <= 0f) return null
        val scale = dpi / 72f
        return width * scale to height * scale
    }
}

@Serializable
internal data class SvgRasterResult(
    @SerialName("output_file") val outputFile: String,
    val dpi: Int,
)
