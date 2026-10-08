package de.moritzf.quota.idea.settings

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.shared.DocumentModels
import kotlin.test.Test
import kotlin.test.assertEquals

class VisionModelSelectionTest {
    @Test
    fun defaultsToOffForEveryProvider() {
        for (type in QuotaProviderType.entries) {
            assertEquals(DocumentModels.OFF, VisionModelSelection.forAccount(type, "account-1"))
        }
    }

    @Test
    fun explicitModelWinsOverSettings() {
        assertEquals(
            "gpt-6-sol",
            VisionModelSelection.forAccount(QuotaProviderType.OPEN_AI, "account-1", " gpt-6-sol "),
        )
        assertEquals(
            "pixtral-large-latest",
            VisionModelSelection.forAccount(
                QuotaProviderType.MISTRAL,
                "account-1",
                "pixtral-large-latest",
            ),
        )
    }

    @Test
    fun explicitOffDisablesVision() {
        assertEquals(
            DocumentModels.OFF,
            VisionModelSelection.forAccount(
                QuotaProviderType.OPEN_AI,
                "account-1",
                DocumentModels.OFF,
            ),
        )
        assertEquals(
            DocumentModels.OFF,
            VisionModelSelection.forAccount(QuotaProviderType.OPEN_AI, "account-1", " - "),
        )
    }
}
