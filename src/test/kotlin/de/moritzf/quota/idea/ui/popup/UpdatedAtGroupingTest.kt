package de.moritzf.quota.idea.ui.popup

import javax.swing.ImageIcon
import kotlin.test.Test
import kotlin.test.assertEquals

class UpdatedAtGroupingTest {
    @Test
    fun groupsProvidersThatShareTheSameUpdateText() {
        val mistral = icon("Mistral")
        val ollama = icon("Ollama")
        val openCode = icon("OpenCode")
        val grouped =
            groupUpdatedAtItems(
                listOf(
                    UpdatedAtItem(listOf(mistral), "just now"),
                    UpdatedAtItem(listOf(ollama), "just now"),
                    UpdatedAtItem(listOf(openCode), "5 minutes ago"),
                )
            )

        assertEquals(
            listOf(
                UpdatedAtItem(listOf(mistral, ollama), "just now"),
                UpdatedAtItem(listOf(openCode), "5 minutes ago"),
            ),
            grouped,
        )
    }

    private fun icon(label: String) = UpdatedAtIcon(label, ImageIcon())
}
