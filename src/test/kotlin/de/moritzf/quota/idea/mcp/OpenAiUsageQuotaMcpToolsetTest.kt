package de.moritzf.quota.idea.mcp

import com.intellij.mcpserver.annotations.McpTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiUsageQuotaMcpToolsetTest {
    @Test
    fun mcpToolsReturnBridgeSafeStrings() {
        val toolMethods = OpenAiUsageQuotaMcpToolset::class.java.declaredMethods
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
    fun imageGenerationUsesSingleToolWithOptionalTargetFile() {
        val imageTools = OpenAiUsageQuotaMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name?.startsWith("codex_image_generation") == true }

        assertEquals(listOf("codex_image_generation"), imageTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(listOf(String::class.java, String::class.java), imageTools.single().parameterTypes.toList())
    }

    @Test
    fun kimiWebSearchUsesSingleToolWithConfigurableOptions() {
        val kimiSearchTools = OpenAiUsageQuotaMcpToolset::class.java.declaredMethods
            .filter { it.getAnnotation(McpTool::class.java)?.name?.startsWith("kimi_web_search") == true }

        assertEquals(listOf("kimi_web_search"), kimiSearchTools.map { it.getAnnotation(McpTool::class.java).name })
        assertEquals(
            listOf(String::class.java, Int::class.java, Boolean::class.java),
            kimiSearchTools.single().parameterTypes.toList(),
        )
    }
}
