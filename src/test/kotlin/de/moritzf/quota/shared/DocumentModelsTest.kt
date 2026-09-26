package de.moritzf.quota.shared

import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.openai.proxy.OpenAiProxyServer
import de.moritzf.quota.supergrok.SuperGrokDocumentClient
import de.moritzf.quota.zai.ZaiOcrClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentModelsTest {
    @Test
    fun ocrDetectionIsPrefixNotAVersionCatalog() {
        assertEquals(MistralOcrClient.DEFAULT_MODEL, DocumentModels.MISTRAL_DEFAULT)
        assertEquals(ZaiOcrClient.DEFAULT_MODEL, DocumentModels.ZAI_DEFAULT)
        assertTrue(DocumentModels.isMistralOcrModel("mistral-ocr-4-1"))
        assertTrue(DocumentModels.isMistralOcrModel("Mistral-Document-AI-2512"))
        assertFalse(DocumentModels.isMistralOcrModel("pixtral-large"))
        assertFalse(DocumentModels.isMistralOcrModel("mistral-large-3"))
        assertTrue(DocumentModels.isZaiOcrModel("glm-ocr"))
        assertTrue(DocumentModels.isZaiOcrModel("glm-ocr-next"))
        assertFalse(DocumentModels.isZaiOcrModel("glm-4.6v"))
    }

    @Test
    fun prefixedChoicesComeFromDiscoveryAndKeepASavedOcrId() {
        val choices = DocumentModels.prefixedChoices(
            listOf("mistral-large-3", "mistral-ocr-4-0", "mistral-ocr-4-1", "mistral-ocr-latest"),
            "mistral-ocr-2505",
            DocumentModels.MISTRAL_DEFAULT,
            DocumentModels::isMistralOcrModel,
        )
        assertEquals(
            listOf("mistral-ocr-latest", "mistral-ocr-4-1", "mistral-ocr-4-0", "mistral-ocr-2505"),
            choices,
        )
        assertEquals(
            listOf(DocumentModels.MISTRAL_DEFAULT),
            DocumentModels.prefixedChoices(emptyList(), "pixtral-large", DocumentModels.MISTRAL_DEFAULT, DocumentModels::isMistralOcrModel),
        )
    }

    @Test
    fun openAiVisionListReusesProxyCatalogAndDropsReserve() {
        val models = DocumentModels.openAiVisionModels(OpenAiProxyServer.advertisedModels())
        assertEquals("gpt-6-sol", DocumentModels.OPEN_AI_DEFAULT)
        assertTrue("gpt-6-astra" in models)
        assertTrue("gpt-5.6-luna" in models)
        assertFalse("gpt-reserve" in models)
    }

    @Test
    fun superGrokDefaultMatchesDocumentClient() {
        assertEquals(SuperGrokDocumentClient.DEFAULT_MODEL, DocumentModels.SUPERGROK_DEFAULT)
    }

    @Test
    fun resolveKeepsDetectedChoiceAndFallsBackWhenMissing() {
        assertEquals("mistral-ocr-4-0", DocumentModels.resolveDetected("mistral-ocr-4-0", DocumentModels.MISTRAL_DEFAULT, DocumentModels::isMistralOcrModel))
        assertEquals(DocumentModels.MISTRAL_DEFAULT, DocumentModels.resolveDetected("pixtral-large", DocumentModels.MISTRAL_DEFAULT, DocumentModels::isMistralOcrModel))
        assertEquals(DocumentModels.MISTRAL_DEFAULT, DocumentModels.resolveDetected(null, DocumentModels.MISTRAL_DEFAULT, DocumentModels::isMistralOcrModel))
    }

    @Test
    fun defaultSelectionIsNotStored() {
        assertEquals(null, DocumentModels.storedSelection(DocumentModels.OPEN_AI_DEFAULT, DocumentModels.OPEN_AI_DEFAULT))
        assertEquals("gpt-5.6-luna", DocumentModels.storedSelection("gpt-5.6-luna", DocumentModels.OPEN_AI_DEFAULT))
        assertFalse(DocumentModels.differs(DocumentModels.OPEN_AI_DEFAULT, null, DocumentModels.OPEN_AI_DEFAULT))
        assertTrue(DocumentModels.differs("gpt-6-luna", null, DocumentModels.OPEN_AI_DEFAULT))
    }

    @Test
    fun superGrokChoicesUseDiscoveryAndDropImagine() {
        val choices = DocumentModels.superGrokChoices(listOf("grok-4.5", "grok-imagine", "grok-4.6"), "grok-4.3")
        assertEquals(listOf("grok-4.6", "grok-4.5", "grok-4.3"), choices)
        assertEquals(listOf("grok-4.7"), DocumentModels.superGrokChoices(emptyList(), null))
    }

    @Test
    fun parseSuperGrokDocumentModelsSkipsImageModels() {
        val body = """
            {"data":[
              {"id":"grok-4.6","prompt_text_token_price":1},
              {"id":"grok-4.5"},
              {"id":"grok-imagine","image_price":1},
              {"id":"flux","image_price":2}
            ]}
        """.trimIndent()
        assertEquals(listOf("grok-4.6", "grok-4.5"), DocumentModels.parseSuperGrokDocumentModelIds(body))
    }

    @Test
    fun parseModelIdsReadsDataOrModels() {
        assertEquals(listOf("mistral-ocr-4-1", "mistral-small"), DocumentModels.parseModelIds("""{"data":[{"id":"mistral-ocr-4-1"},{"id":"mistral-small"}]}"""))
        assertEquals(listOf("glm-ocr"), DocumentModels.parseModelIds("""{"models":[{"id":"glm-ocr"}]}"""))
    }
}
