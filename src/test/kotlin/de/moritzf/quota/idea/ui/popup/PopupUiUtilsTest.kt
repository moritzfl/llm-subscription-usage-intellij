package de.moritzf.quota.idea.ui.popup

import kotlin.test.Test
import kotlin.test.assertEquals

class PopupUiUtilsTest {
    @Test
    fun toDisplayLabelStripsSelfServeAndTitleCasesWords() {
        assertEquals("Business Prolite", "self_serve_business_prolite".toDisplayLabel())
        assertEquals("Business Usage Based", "self_serve_business_usage_based".toDisplayLabel())
        assertEquals("Business Prolite", "SELF_SERVE_BUSINESS_PROLITE".toDisplayLabel())
        assertEquals("Plus", "plus".toDisplayLabel())
        assertEquals("Prolite", "prolite".toDisplayLabel())
        assertEquals("Team", "team".toDisplayLabel())
        assertEquals("", "self_serve".toDisplayLabel())
        assertEquals("Business Prolite", "  self_serve_business_prolite  ".toDisplayLabel())
    }

    @Test
    fun codexPlanLabelsMatchCodexStatus() {
        assertEquals("Pro 100", codexPlanLabel("prolite"))
        assertEquals("Pro 200", codexPlanLabel("pro"))
        assertEquals("Pro 500", codexPlanLabel("PROMAX"))
        assertEquals("Business", codexPlanLabel("team"))
        assertEquals("Business Premium", codexPlanLabel("self_serve_business_prolite"))
        assertEquals("Enterprise", codexPlanLabel("ent26"))
        assertEquals("Enterprise (Automation)", codexPlanLabel("enterprise_cbp_automation"))
        assertEquals("Academic Researchers Plus", codexPlanLabel("sci_plus"))
        assertEquals("Edu Pro", codexPlanLabel("edu_pro"))
        assertEquals("Plus", codexPlanLabel(" plus "))
        assertEquals("Future Tier", codexPlanLabel("future_tier"))
    }
}
