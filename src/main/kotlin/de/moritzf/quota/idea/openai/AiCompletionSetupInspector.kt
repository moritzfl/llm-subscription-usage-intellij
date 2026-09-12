package de.moritzf.quota.idea.openai

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import de.moritzf.proxy.fim.CompletionsConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

data class AiCompletionSetupReport(
    val pluginFound: Boolean,
    val baseUrlFound: Boolean,
    val modelFound: Boolean,
    val summary: String,
)

object AiCompletionSetupInspector {
    val AI_ASSISTANT_PLUGIN_IDS = listOf(
        "com.intellij.ml.llm",
        "com.intellij.ml.llm.fullOnPrem",
    )

    fun inspect(
        baseUrl: String,
        modelId: String = CompletionsConfig.FIM_ALIAS_ID,
        pluginFound: Boolean = isAiAssistantEnabled(),
        optionsDir: Path? = optionsDirectory(),
    ): AiCompletionSetupReport {
        val haystack = optionsDir?.let(::readOptionsText).orEmpty()
        val normalizedBase = normalizeUrl(baseUrl)
        val baseUrlFound = normalizedBase.isNotEmpty() && haystack.contains(normalizedBase)
        val modelFound = modelId.isNotBlank() && haystack.contains(modelId)
        val summary = when {
            !pluginFound ->
                "JetBrains AI Assistant was not found. Install it to use official inline completion."
            !baseUrlFound && !modelFound ->
                "AI Completion is not pointed at this proxy yet. Follow the setup steps above."
            baseUrlFound && modelFound ->
                "AI Completion looks configured (proxy URL and $modelId). Auto should pick (fim) Qwen."
            baseUrlFound ->
                "Proxy URL found in IDE settings, but the model is not $modelId."
            else ->
                "Model $modelId found in IDE settings, but the proxy base URL was not."
        }
        return AiCompletionSetupReport(
            pluginFound = pluginFound,
            baseUrlFound = baseUrlFound,
            modelFound = modelFound,
            summary = summary,
        )
    }

    fun isAiAssistantEnabled(): Boolean {
        return AI_ASSISTANT_PLUGIN_IDS.any { id ->
            val pluginId = PluginId.getId(id)
            PluginManagerCore.getPlugin(pluginId) != null && !PluginManagerCore.isDisabled(pluginId)
        }
    }

    private fun optionsDirectory(): Path? {
        return runCatching { Path.of(PathManager.getOptionsPath()) }.getOrNull()
    }

    private fun readOptionsText(optionsDir: Path): String {
        if (!Files.isDirectory(optionsDir)) return ""
        return Files.list(optionsDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension in OPTIONS_EXTENSIONS }
                .mapNotNull { path -> runCatching { path.readText() }.getOrNull() }
                .joinToString("\n")
        }
    }

    internal fun normalizeUrl(url: String): String = url.trim().trimEnd('/')

    private val OPTIONS_EXTENSIONS = setOf("xml", "json", "jsonc")
}
