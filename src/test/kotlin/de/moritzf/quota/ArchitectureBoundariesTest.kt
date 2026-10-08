package de.moritzf.quota

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureBoundariesTest {
    private val root = Path.of("src/main/kotlin/de/moritzf")

    @Test
    fun proxyAndProviderClientsDoNotAcquireNewIdeDependencies() {
        // Intentional platform runtime bindings and existing small auth value/codec dependencies.
        val allowed =
            mapOf(
                "proxy/server/ApiKeyStore.kt" to
                    setOf("com.intellij.concurrency.virtualThreads.IntelliJVirtualThreads"),
                "proxy/logging/RequestLogger.kt" to
                    setOf("com.intellij.concurrency.virtualThreads.IntelliJVirtualThreads"),
                "quota/supergrok/SuperGrokQuotaClient.kt" to
                    setOf("com.intellij.openapi.diagnostic.Logger"),
                "quota/kimi/KimiDeviceHeaders.kt" to
                    setOf("com.intellij.ide.util.PropertiesComponent"),
                "quota/github/GitHubOAuthClient.kt" to
                    setOf("de.moritzf.quota.idea.auth.OAuthUrlCodec"),
                "quota/kimi/KimiCredentialRefresher.kt" to
                    setOf("de.moritzf.quota.idea.auth.OAuthUrlCodec"),
                "quota/opencode/OpenCodeOAuthClient.kt" to
                    setOf("de.moritzf.quota.idea.auth.OAuthCredentials"),
            )
        val violations = mutableListOf<String>()
        sources()
            .filterNot { root.relativize(it).toString().startsWith("quota/idea/") }
            .forEach { file ->
                val relative = root.relativize(file).toString()
                imports(file)
                    .filter {
                        it.startsWith("com.intellij.") || it.startsWith("de.moritzf.quota.idea.")
                    }
                    .filterNot { it in allowed[relative].orEmpty() }
                    .forEach { violations += "$relative: $it" }
            }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun applicationOperationsDoNotDeclareMcpToolsOrDependOnSettingsWidgets() {
        val files =
            sources().filter { root.relativize(it).toString().startsWith("quota/idea/operations/") }
        assertTrue(files.isNotEmpty())
        val violations = files.flatMap { file ->
            imports(file)
                .filter {
                    it.startsWith("com.intellij.mcpserver.annotations.") ||
                        it.contains("SettingsPanel") ||
                        it.contains("SettingsConfigurable") ||
                        it == "com.intellij.mcpserver.McpToolset"
                }
                .map { "$file: $it" }
        }
        assertEquals(emptyList(), violations)
        val facade = root.resolve("quota/idea/mcp/SubscriptionUsageMcpToolset.kt").readText()
        assertTrue(
            !facade.contains("getInstance()"),
            "MCP facade must delegate service lookup and orchestration",
        )
    }

    private fun sources(): List<Path> =
        Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }

    private fun imports(file: Path) =
        file
            .readText()
            .lineSequence()
            .filter { it.startsWith("import ") }
            .map { it.removePrefix("import ").trim() }
            .toList()
}
