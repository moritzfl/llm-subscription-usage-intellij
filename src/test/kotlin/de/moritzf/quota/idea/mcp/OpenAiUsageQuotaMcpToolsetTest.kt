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
}
