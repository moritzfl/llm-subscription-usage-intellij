package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.annotations.McpTool
import de.moritzf.quota.idea.common.QuotaProviderType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.functions
import kotlin.reflect.full.valueParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubscriptionUsageMcpToolsetTest {
    @Test
    fun usageQuotaMcpRegistryCoversEveryProviderType() {
        assertEquals(QuotaProviderType.entries.toSet(), UsageQuotaMcpRegistry.all.keys)
    }

    @Test
    fun mcpToolsReturnBridgeSafeStrings() {
        val bad = mcpTools()
            .filterNot { it.returnType.classifier == String::class }
            .map { it.name }
        assertTrue(mcpTools().isNotEmpty())
        assertEquals(emptyList(), bad)
    }

    @Test
    fun subscriptionImageGenerationUsesSingleToolWithProviderEnum() {
        val imageTools = mcpTools("subscription_image_generation")
        val legacyImageTools = mcpTools()
            .mapNotNull { it.findAnnotation<McpTool>()?.name }
            .filter { it == "codex_image_generation" || it == "supergrok_image_generation" }

        assertEquals(emptyList(), legacyImageTools)
        assertEquals(listOf("subscription_image_generation"), imageTools.map { it.mcpName() })
        assertEquals(
            listOf(String::class, ImageGenerationProvider::class, String::class),
            imageTools.single().mcpParamClassifiers(),
        )
    }

    @Test
    fun superGrokVideoGenerationUsesSingleToolWithOptionalImageAndPolling() {
        val videoTools = mcpTools("supergrok_video_generation")

        assertEquals(listOf("supergrok_video_generation"), videoTools.map { it.mcpName() })
        assertEquals(
            listOf(
                String::class,
                String::class,
                Int::class,
                String::class,
                Boolean::class,
                Int::class,
                String::class,
            ),
            videoTools.single().mcpParamClassifiers(),
        )
    }

    @Test
    fun subscriptionToolsStatusAcceptsOptionalCapabilityAndModel() {
        val statusTools = mcpTools().filter { it.mcpName()?.startsWith("subscription_tools_status") == true }

        assertEquals(listOf("subscription_tools_status"), statusTools.map { it.mcpName() })
        assertEquals(
            listOf(de.moritzf.quota.idea.settings.AccountCapability::class, String::class),
            statusTools.single().mcpParamClassifiers(),
        )
    }

    @Test
    fun subscriptionQuotaUsesSingleToolWithProviderEnumParameter() {
        val toolNames = mcpTools().mapNotNull { it.mcpName() }
        val quotaToolNames = toolNames.filter { it == "subscription_quota" || it.endsWith("_usage_quota") }
        val quotaTool = mcpTools("subscription_quota").single()

        assertEquals(listOf("subscription_quota"), quotaToolNames)
        assertEquals(listOf(QuotaProviderType::class, String::class), quotaTool.mcpParamClassifiers())
    }

    @Test
    fun codexWebSearchUsesSingleToolWithConfigurableOptions() {
        val searchTools = mcpTools().filter { it.mcpName()?.startsWith("codex_web_search") == true }

        assertEquals(listOf("codex_web_search"), searchTools.map { it.mcpName() })
        assertEquals(
            listOf(
                String::class,
                String::class,
                Boolean::class,
                Boolean::class,
                String::class,
                String::class,
            ),
            searchTools.single().mcpParamClassifiers(),
        )
    }

    @Test
    fun superGrokWebSearchUsesSingleToolWithConfigurableOptions() {
        val searchTools = mcpTools().filter { it.mcpName()?.startsWith("supergrok_web_search") == true }

        assertEquals(listOf("supergrok_web_search"), searchTools.map { it.mcpName() })
        assertEquals(
            listOf(String::class, String::class, String::class, String::class, Int::class),
            searchTools.single().mcpParamClassifiers(),
        )
    }

    @Test
    fun subscriptionSpeechToolsUseProviderEnums() {
        val stt = mcpTools("subscription_speech_to_text").single()
        val tts = mcpTools("subscription_text_to_speech").single()
        val voices = mcpTools("subscription_list_voices").single()

        assertEquals(
            listOf(
                SpeechToTextProvider::class,
                String::class,
                String::class,
                String::class,
                Boolean::class,
                String::class,
            ),
            stt.mcpParamClassifiers(),
        )
        assertEquals(
            listOf(
                String::class,
                TextToSpeechProvider::class,
                String::class,
                String::class,
                String::class,
                String::class,
                String::class,
            ),
            tts.mcpParamClassifiers(),
        )
        assertEquals(listOf(TextToSpeechProvider::class), voices.mcpParamClassifiers())
    }

    @Test
    fun subscriptionDocumentToMarkdownUsesMistralOcrPaths() {
        val tools = mcpTools("subscription_document_to_markdown")

        assertEquals(listOf("subscription_document_to_markdown"), tools.map { it.mcpName() })
        assertEquals(
            listOf(
                DocumentToMarkdownProvider::class,
                String::class,
                String::class,
                String::class,
                Boolean::class,
                String::class,
                Int::class,
                Int::class,
            ),
            tools.single().mcpParamClassifiers(),
        )
    }

    @Test
    fun subscriptionWebSearchUsesSingleToolWithProviderEnumParameter() {
        val searchTools = mcpTools().filter { it.mcpName()?.startsWith("subscription_web_search") == true }

        assertEquals(listOf("subscription_web_search"), searchTools.map { it.mcpName() })
        assertEquals(
            listOf(ListSearchProvider::class, String::class, Int::class, Boolean::class),
            searchTools.single().mcpParamClassifiers(),
        )
    }

    private fun mcpTools(name: String? = null): List<KFunction<*>> {
        return SubscriptionUsageMcpToolset::class.functions
            .filter { it.findAnnotation<McpTool>() != null }
            .filter { name == null || it.mcpName() == name }
    }

    private fun KFunction<*>.mcpName(): String? = findAnnotation<McpTool>()?.name

    private fun KFunction<*>.mcpParamClassifiers(): List<KClass<*>> {
        return valueParameters.map { it.type.classifier as KClass<*> }
    }
}
