package de.moritzf.quota.shared

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Document-model selection. Native OCR ids are detected from provider model lists by prefix.
 * Version catalogs are not maintained here.
 */
internal object DocumentModels {
    const val OPEN_AI_DEFAULT = "gpt-6-sol"
    const val SUPERGROK_DEFAULT = "grok-4.7"
    const val MISTRAL_DEFAULT = "mistral-ocr-latest"
    const val ZAI_DEFAULT = "glm-ocr"

    const val OCR_RECOMMENDATION =
        "Use a document or OCR model (Mistral, Z.ai). A company OCR model, such as Azure, is also a good choice when you have one."

    const val VISION_WARNING =
        "This is a general vision model, not a dedicated document or OCR model. Results are usually less precise, " +
            "and a long PDF costs more.\n\n$OCR_RECOMMENDATION"

    const val AZURE_VISION_WARNING =
        "This deployment only reads the document as vision, not as OCR. Results are usually less precise, " +
            "and a long PDF costs more.\n\n" +
            "A document or OCR model deployed on this Azure resource, such as Mistral, usually does better."

    const val PDFBOX_WARNING =
        "PDFBox only extracts embedded text. It does not OCR scans, rebuild tables, or export figures. " +
            "Reading order and layout are often wrong. It is free and needs no subscription.\n\n$OCR_RECOMMENDATION"

    fun isMistralOcrModel(id: String): Boolean {
        val model = id.trim()
        return model.startsWith("mistral-ocr-", ignoreCase = true) ||
            model.startsWith("mistral-document-ai-", ignoreCase = true)
    }

    fun isZaiOcrModel(id: String): Boolean = id.trim().startsWith("glm-ocr", ignoreCase = true)

    /** Proxy catalog minus the reserve hop. Codex has no usable live model list. */
    fun openAiVisionModels(advertised: List<String>): List<String> =
        advertised.filter { it.isNotBlank() && it != "gpt-reserve" }

    fun prefixedChoices(discovered: List<String>, saved: String?, defaultModel: String, accept: (String) -> Boolean): List<String> {
        val ids = (discovered + listOfNotNull(saved?.trim()?.takeIf { it.isNotEmpty() }))
            .map { it.trim() }
            .filter(accept)
            .distinct()
        if (ids.isEmpty()) return listOf(defaultModel)
        val rest = ids.filter { !it.equals(defaultModel, ignoreCase = true) }.sortedDescending()
        return if (ids.any { it.equals(defaultModel, ignoreCase = true) }) listOf(defaultModel) + rest else rest
    }

    fun resolveDetected(saved: String?, defaultModel: String, accept: (String) -> Boolean): String {
        val trimmed = saved?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && accept(trimmed)) trimmed else defaultModel
    }

    fun resolve(saved: String?, choices: List<String>, defaultModel: String): String {
        val trimmed = saved?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && trimmed in choices) trimmed else defaultModel
    }

    fun storedSelection(selected: String?, defaultModel: String): String? =
        selected?.trim()?.takeIf { it.isNotEmpty() && it != defaultModel }

    fun differs(selected: String?, saved: String?, defaultModel: String): Boolean =
        storedSelection(selected, defaultModel).orEmpty() !=
            saved?.trim()?.takeIf { it.isNotEmpty() && it != defaultModel }.orEmpty()

    /** Discovered text models. grok-4.7 is only the fallback when discovery is empty. */
    fun superGrokChoices(discovered: List<String>, saved: String?): List<String> =
        prefixedChoices(discovered, saved, SUPERGROK_DEFAULT, ::isSuperGrokDocumentModel)

    fun isSuperGrokDocumentModel(id: String): Boolean {
        val trimmed = id.trim()
        return trimmed.isNotEmpty() && !trimmed.contains("imagine", ignoreCase = true)
    }

    fun parseModelIds(body: String): List<String> = parseModelEntries(body).map { it.id }

    fun parseSuperGrokDocumentModelIds(body: String): List<String> {
        return parseModelEntries(body).mapNotNull { entry ->
            if (!isSuperGrokTextModel(entry.id, entry.item)) return@mapNotNull null
            entry.id
        }.distinct()
    }

    fun fetchModelIds(uri: URI, bearer: String): List<String> {
        val token = bearer.trim()
        if (token.isEmpty()) return emptyList()
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .header("User-Agent", "openai-usage-quota-intellij")
            .GET()
            .build()
        val response = runCatching {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
        }.getOrNull() ?: return emptyList()
        if (response.statusCode() !in 200..299) return emptyList()
        return parseModelIds(response.body())
    }

    private data class ModelEntry(val id: String, val item: JsonObject)

    private fun parseModelEntries(body: String): List<ModelEntry> {
        val root = runCatching { JsonSupport.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val data = root["data"] as? JsonArray ?: root["models"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = (item["id"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            ModelEntry(id, item)
        }
    }

    private fun isSuperGrokTextModel(id: String, item: JsonObject): Boolean {
        if (item["prompt_text_token_price"] != null || item["completion_text_token_price"] != null) return true
        if (item["image_price"] != null || id.contains("imagine", ignoreCase = true)) return false
        return true
    }
}
