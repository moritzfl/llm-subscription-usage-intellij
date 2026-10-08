package de.moritzf.quota.idea.ui.settings

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderListIconTest {
    @Test
    fun providerSvgsAreListSized() {
        val iconsDir = Path.of("src/main/resources/icons")
        val names =
            listOf(
                "openai",
                "opencode",
                "ollama",
                "mistral",
                "supergrok",
                "claude",
                "cursor",
                "github",
                "kimi",
                "minimax",
                "zai",
            )
        for (name in names) {
            for (file in listOf("$name.svg", "${name}_dark.svg")) {
                val svg = iconsDir.resolve(file).readText()
                val width = WIDTH_ATTRIBUTE.find(svg)?.groupValues?.get(1)?.toInt()
                if (width != null) {
                    assertTrue(width <= 32, "$file width=$width")
                }
            }
        }
    }

    companion object {
        private val WIDTH_ATTRIBUTE = Regex("""\bwidth=['"](\d+)""")
    }
}
