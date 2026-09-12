package de.moritzf.quota.idea.openai

import de.moritzf.proxy.fim.CompletionsConfig
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiCompletionSetupInspectorTest {
    @Test
    fun detectsBaseUrlAndModelInOptions() {
        val dir = Files.createTempDirectory("fim-setup")
        Files.writeString(
            dir.resolve("llm.xml"),
            """<application><component name="Llm"><option name="url" value="http://127.0.0.1:14621"/><option name="model" value="${CompletionsConfig.FIM_ALIAS_ID}"/></component></application>""",
        )

        val report = AiCompletionSetupInspector.inspect(
            baseUrl = "http://127.0.0.1:14621/",
            modelId = CompletionsConfig.FIM_ALIAS_ID,
            pluginFound = true,
            optionsDir = dir,
            powerSaveEnabled = false,
        )

        assertTrue(report.baseUrlFound)
        assertTrue(report.modelFound)
        assertTrue(report.summary.contains("looks configured"))
        assertTrue(report.summary.contains("gray suggestions"))
    }

    @Test
    fun treatsLocalhostAsLoopback() {
        val dir = Files.createTempDirectory("fim-setup-localhost")
        Files.writeString(
            dir.resolve("llm.next.edit.providers.xml"),
            """<application><component name="NextEditProviderSettings"><![CDATA[{
              "selectedProvider":{"kind":"OPENAI_COMPATIBLE"},
              "openAiCompatible":{"baseUrl":"http://localhost:14621","model":"${CompletionsConfig.FIM_ALIAS_ID}","schemaId":"fim.qwen"}
            }]]></component></application>""",
        )

        val report = AiCompletionSetupInspector.inspect(
            baseUrl = "http://127.0.0.1:14621",
            pluginFound = true,
            optionsDir = dir,
            powerSaveEnabled = false,
        )

        assertTrue(report.baseUrlFound)
        assertTrue(report.modelFound)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun warnsOnZetaSchemaAndPowerSave() {
        val dir = Files.createTempDirectory("fim-setup-zeta")
        Files.writeString(
            dir.resolve("llm.next.edit.providers.xml"),
            """<application><component name="NextEditProviderSettings"><![CDATA[{
              "selectedProvider":{"kind":"OPENAI_COMPATIBLE"},
              "openAiCompatible":{"baseUrl":"http://127.0.0.1:14621","model":"${CompletionsConfig.FIM_ALIAS_ID}","schemaId":"zeta"}
            }]]></component></application>""",
        )

        val report = AiCompletionSetupInspector.inspect(
            baseUrl = "http://127.0.0.1:14621",
            pluginFound = true,
            optionsDir = dir,
            powerSaveEnabled = true,
        )

        assertTrue(report.baseUrlFound)
        assertTrue(report.modelFound)
        assertTrue(report.issues.any { it.contains("zeta") })
        assertTrue(report.issues.any { it.contains("Power Save") })
        assertTrue(report.summary.contains("but:"))
    }

    @Test
    fun reportsMissingConfiguration() {
        val dir = Files.createTempDirectory("fim-setup-empty")
        val report = AiCompletionSetupInspector.inspect(
            baseUrl = "http://127.0.0.1:14621",
            pluginFound = true,
            optionsDir = dir,
            powerSaveEnabled = false,
        )

        assertFalse(report.baseUrlFound)
        assertFalse(report.modelFound)
        assertTrue(report.summary.contains("not pointed at this proxy"))
    }
}
