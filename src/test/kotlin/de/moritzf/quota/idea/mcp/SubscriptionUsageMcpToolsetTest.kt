package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.annotations.McpTool
import de.moritzf.quota.idea.common.QuotaProviderType
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
        val toolMethods = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java) != null }

        assertTrue(toolMethods.isNotEmpty())
        assertEquals(
            emptyList(),
            toolMethods
                .filterNot { it.returnType == String::class.java }
                .map { "${it.name}: ${it.returnType.name}" },
        )
    }

    @Test
    fun subscriptionImageGenerationUsesSingleToolWithProviderEnum() {
        val imageTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name == "subscription_image_generation" }
        val legacyImageTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(McpTool::class.java)?.name }
            .filter { it == "codex_image_generation" || it == "supergrok_image_generation" }

        assertEquals(emptyList(), legacyImageTools)
        assertEquals(listOf("subscription_image_generation"), imageTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(
            listOf(String::class.java, ImageGenerationProvider::class.java),
            imageTools.single().parameterTypes.toList(),
        )
    }

    @Test
    fun superGrokVideoGenerationUsesSingleToolWithOptionalImageAndPolling() {
        val videoTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name == "supergrok_video_generation" }

        assertEquals(listOf("supergrok_video_generation"), videoTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(
            listOf(
                String::class.java,
                String::class.java,
                Int::class.java,
                String::class.java,
                Boolean::class.java,
                Int::class.java,
            ),
            videoTools.single().parameterTypes.toList(),
        )
    }

    @Test
    fun subscriptionToolsStatusUsesSingleNoArgTool() {
        val statusTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name?.startsWith("subscription_tools_status") == true }

        assertEquals(listOf("subscription_tools_status"), statusTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(emptyList(), statusTools.single().parameterTypes.toList())
    }

    @Test
    fun subscriptionQuotaUsesSingleToolWithProviderEnumParameter() {
        val toolNames = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(McpTool::class.java)?.name }
        val quotaToolNames = toolNames.filter { it == "subscription_quota" || it.endsWith("_usage_quota") }
        val quotaTool = SubscriptionUsageMcpToolset::class.java.declaredMethods.single {
            it.getAnnotation(McpTool::class.java)?.name == "subscription_quota"
        }

        assertEquals(listOf("subscription_quota"), quotaToolNames)
        assertEquals(listOf(QuotaProviderType::class.java), quotaTool.parameterTypes.toList())
    }

    @Test
    fun codexWebSearchUsesSingleToolWithConfigurableOptions() {
        val searchTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name?.startsWith("codex_web_search") == true }

        assertEquals(listOf("codex_web_search"), searchTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(
            listOf(
                String::class.java,
                String::class.java,
                Boolean::class.java,
                Boolean::class.java,
                String::class.java,
                String::class.java,
            ),
            searchTools.single().parameterTypes.toList(),
        )
    }

    @Test
    fun superGrokWebSearchUsesSingleToolWithConfigurableOptions() {
        val searchTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name?.startsWith("supergrok_web_search") == true }

        assertEquals(listOf("supergrok_web_search"), searchTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(
            listOf(String::class.java, String::class.java, String::class.java, String::class.java, Int::class.java),
            searchTools.single().parameterTypes.toList(),
        )
    }

    @Test
    fun subscriptionWebSearchUsesSingleToolWithProviderEnumParameter() {
        val searchTools = SubscriptionUsageMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name?.startsWith("subscription_web_search") == true }

        assertEquals(listOf("subscription_web_search"), searchTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(
            listOf(ListSearchProvider::class.java, String::class.java, Int::class.java, Boolean::class.java),
            searchTools.single().parameterTypes.toList(),
        )
    }
}