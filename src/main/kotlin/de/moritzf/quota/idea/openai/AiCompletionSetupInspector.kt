package de.moritzf.quota.idea.openai

import com.intellij.ide.PowerSaveMode
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.extensions.PluginId
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.server.JsonHelper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

data class AiCompletionSetupReport(
    val pluginFound: Boolean,
    val baseUrlFound: Boolean,
    val modelFound: Boolean,
    val issues: List<String> = emptyList(),
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
        powerSaveEnabled: Boolean = runCatching { PowerSaveMode.isEnabled() }.getOrDefault(false),
    ): AiCompletionSetupReport {
        val files = optionsDir?.let(::readOptionFiles).orEmpty()
        val haystack = files.values.joinToString("\n")
        val normalizedBase = normalizeUrl(baseUrl)
        val providers = files.entries.firstOrNull { it.key.contains("next.edit.providers") }?.value?.let(::extractJson)
        val nextEdits = files.entries.firstOrNull { it.key.contains("nextEdits") && !it.key.contains("providers") }?.value?.let(::extractJson)
        val issues = ArrayList<String>()

        val selectedKind = providers
            ?.jsonObject("selectedProvider")
            ?.string("kind")
        val openAi = providers?.jsonObject("openAiCompatible")
        val configuredUrl = openAi?.string("baseUrl")?.let(::normalizeUrl)
        val configuredModel = openAi?.string("model")
        val schemaId = openAi?.string("schemaId").orEmpty()

        val baseUrlFound = when {
            configuredUrl != null -> configuredUrl == normalizedBase
            else -> normalizedBase.isNotEmpty() && normalizeUrl(haystack).contains(normalizedBase)
        }
        val modelFound = when {
            configuredModel != null -> configuredModel == modelId
            else -> modelId.isNotBlank() && haystack.contains(modelId)
        }

        if (selectedKind != null && selectedKind != "OPENAI_COMPATIBLE") {
            issues += "AI Completion provider is $selectedKind, not OpenAI Compatible."
        }
        if (schemaId.startsWith("zeta", ignoreCase = true) || schemaId.startsWith("sweep", ignoreCase = true)) {
            issues += "Prompt schema is $schemaId. Set it to Auto."
        }
        if (nextEdits?.boolean("enabled") == false) {
            issues += "Next Edit suggestions are off. Turn them on under Editor → General → Code Completion → Inline."
        }
        if (powerSaveEnabled) {
            issues += "Power Save mode is on; inline completion is often paused."
        }
        val languages = nextEdits?.jsonObject("languageToEnabled")
        if (nextEdits?.boolean("limitFileTypes") != false && languages != null) {
            val kotlinOn = languages.boolean("kotlin") == true
            val javaOn = languages.boolean("java") == true || languages.boolean("JAVA") == true
            if (!kotlinOn && !javaOn) {
                issues += "Kotlin/Java are not in Display Suggestions For."
            }
        }

        val summary = when {
            !pluginFound ->
                "JetBrains AI Assistant was not found. Install it to use AI Completion in the editor."
            !baseUrlFound && !modelFound ->
                "AI Completion is not pointed at this proxy yet. Follow the setup steps above."
            baseUrlFound && modelFound && issues.isEmpty() ->
                "AI Completion looks configured (proxy URL and $modelId). Type in the editor and wait for gray suggestions."
            baseUrlFound && modelFound ->
                "AI Completion URL and model match, but: ${issues.joinToString(" ")}"
            baseUrlFound ->
                "Proxy URL found in IDE settings, but the model is not $modelId."
            else ->
                "Model $modelId found in IDE settings, but the proxy base URL was not."
        }
        return AiCompletionSetupReport(
            pluginFound = pluginFound,
            baseUrlFound = baseUrlFound,
            modelFound = modelFound,
            issues = issues,
            summary = summary,
        )
    }

    fun isAiAssistantEnabled(): Boolean {
        return AI_ASSISTANT_PLUGIN_IDS.any { id ->
            val pluginId = PluginId.getId(id)
            PluginManagerCore.isPluginInstalled(pluginId) && !PluginManagerCore.isDisabled(pluginId)
        }
    }

    internal fun normalizeUrl(url: String): String {
        return url.trim().trimEnd('/')
            .replace("http://localhost", "http://127.0.0.1")
            .replace("http://[::1]", "http://127.0.0.1")
    }

    private fun optionsDirectory(): Path? {
        return runCatching { Path.of(PathManager.getOptionsPath()) }.getOrNull()
    }

    private fun readOptionFiles(optionsDir: Path): Map<String, String> {
        if (!Files.isDirectory(optionsDir)) return emptyMap()
        return Files.list(optionsDir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension in OPTIONS_EXTENSIONS }
                .mapNotNull { path ->
                    val text = runCatching { path.readText() }.getOrNull() ?: return@mapNotNull null
                    path.fileName.toString() to text
                }
                .toMap()
        }
    }

    private fun extractJson(xml: String): JsonObject? {
        val start = xml.indexOf('{')
        val end = xml.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return JsonHelper.parseToJsonElementOrNull(xml.substring(start, end + 1)) as? JsonObject
    }

    private fun JsonObject.jsonObject(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? {
        val value = this[key] as? JsonPrimitive ?: return null
        return value.booleanOrNull ?: value.contentOrNull?.toBooleanStrictOrNull()
    }

    private val OPTIONS_EXTENSIONS = setOf("xml", "json", "jsonc")
}
