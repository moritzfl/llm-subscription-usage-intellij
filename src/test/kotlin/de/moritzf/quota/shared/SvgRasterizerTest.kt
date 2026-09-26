package de.moritzf.quota.shared

import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SvgRasterizerTest {
    @Test
    fun pngSizeFollowsPdfPointsAndDpi() {
        val directory = Files.createTempDirectory("svg-raster")
        val svg = directory.resolve("figure.svg")
        Files.writeString(
            svg,
            """<svg xmlns="http://www.w3.org/2000/svg" width="72" height="36"><rect width="72" height="36" fill="red"/></svg>""",
        )
        try {
            val json = SvgRasterizer.toPng(svg, null, 144)
            val png = directory.resolve("figure.png")
            assertTrue(json.contains(png.toString()))
            val image = ImageIO.read(png.toFile())
            assertEquals(144, image.width)
            assertEquals(72, image.height)
        } finally {
            svg.toFile().delete()
            directory.resolve("figure.png").toFile().delete()
            directory.toFile().delete()
        }
    }
}
