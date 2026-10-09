package de.moritzf.quota.azure

import kotlin.test.Test
import kotlin.test.assertEquals

class AzureVisionModelsTest {
    @Test
    fun usesDeploymentNamesAndExcludesOcrEmbeddingsAndOtherResources() {
        val quota =
            AzureQuota(
                models = listOf("chat", "ocr", "embedding", "other", "fallback"),
                windows =
                    listOf(
                        deployment("chat", "gpt-4o"),
                        deployment("ocr", "mistral-ocr-4-0"),
                        deployment("embedding", "text-embedding-3-large"),
                        deployment("other", "gpt-4o", "other-resource"),
                    ),
            )
        assertEquals(
            listOf("chat", "fallback", "manual", "saved"),
            azureVisionChoices(quota, "manual, ocr embedding other", "saved", "resource"),
        )
    }

    @Test
    fun preservesManualSelectionWithoutDiscoveryButNeverOffersOffAsAModel() {
        assertEquals(
            listOf("chat", "saved"),
            azureVisionChoices(
                null,
                "chat, chat mistral-ocr-4-0 text-embedding-3-large",
                "saved",
                null,
            ),
        )
        assertEquals(emptyList(), azureVisionChoices(null, null, "-", null))
    }

    private fun deployment(id: String, model: String, resource: String = "resource") =
        AzureUsageWindow(
            id,
            "",
            AzureUsageWindow.DEPLOYMENT,
            resourceName = resource,
            modelName = model,
        )
}
