package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.settings.QuotaSettingsConfigurable as Configuration

/**
 * Deliberate violations imported explicitly by ArchitectureRulesTest, never by production rules.
 */
internal object ArchitectureOperationFixtures {
    class Annotated {
        @com.intellij.mcpserver.annotations.McpTool fun misplacedTool() = "result"
    }

    class Widget(val configuration: Configuration)
}
