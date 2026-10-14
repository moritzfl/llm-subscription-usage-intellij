package de.moritzf.quota.litellm

import de.moritzf.proxy.subscription.OpenAiCompatibleApiKeySubscriptionProxyProvider.StaticModel
import de.moritzf.quota.compatible.CompatibleEndpoint
import de.moritzf.quota.shared.JsonSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.*

/** Optional LiteLLM extensions. Inference still uses the shared OpenAI-compatible transport. */
class LiteLlmClient(
    private val endpoint: CompatibleEndpoint,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
) {
    fun enrichModels(apiKey: String, models: List<StaticModel>): List<StaticModel> {
        val entries =
            try {
                (get(endpoint.uri("model/info"), apiKey)["data"] as? JsonArray).orEmpty()
            } catch (cancelled: InterruptedException) {
                Thread.currentThread().interrupt()
                throw cancelled
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return models // Management permissions are not required for inference.
            }
        val byName = entries.filterIsInstance<JsonObject>().groupBy { it.text("model_name") }
        // /models remains authoritative for access and public aliases. Never expose deployment IDs.
        return models.mapNotNull { model ->
            val deployments =
                byName[model.id]
                    ?.map { it["model_info"] as? JsonObject ?: JsonObject(emptyMap()) }
                    ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull model
            fun <T> agreed(read: (JsonObject) -> T?): T? =
                deployments.map(read).distinct().singleOrNull()
            val mode = agreed { it.text("mode") }
            if (mode != null && mode !in setOf("chat", "completion", "responses"))
                return@mapNotNull null
            model.copy(
                supportsFunctionCalling = agreed { it.bool("supports_function_calling") },
                supportsToolChoice = agreed { it.bool("supports_tool_choice") },
                supportsVision = model.supportsVision ?: agreed { it.bool("supports_vision") },
                supportsPdfInput =
                    model.supportsPdfInput ?: agreed { it.bool("supports_pdf_input") },
                supportsParallelFunctionCalling =
                    agreed { it.bool("supports_parallel_function_calling") },
                supportsPromptCaching = agreed { it.bool("supports_prompt_caching") },
                maxInputTokens =
                    agreed { it.positiveInt("max_input_tokens") } ?: model.maxInputTokens,
                maxOutputTokens =
                    agreed { it.positiveInt("max_output_tokens") } ?: model.maxOutputTokens,
                inputCostPerToken = agreed { it.nonNegative("input_cost_per_token") },
                outputCostPerToken = agreed { it.nonNegative("output_cost_per_token") },
            )
        }
    }

    fun quota(apiKey: String): LiteLlmQuota =
        LiteLlmQuota.parse(get(endpoint.managementUri("key/info"), apiKey))

    private fun get(uri: URI, key: String): JsonObject {
        require(key.isNotBlank()) { "Enter a LiteLLM virtual key." }
        val response =
            httpClient.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer ${key.trim()}")
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() in 200..299) {
            "LiteLLM ${uri.path.substringAfterLast('/')} unavailable (HTTP ${response.statusCode()}). Check key permissions and the gateway URL."
        }
        return JsonSupport.json.parseToJsonElement(response.body()) as? JsonObject
            ?: error("LiteLLM returned an invalid response.")
    }
}

internal fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.nonNegative(key: String) =
    (get(key) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }

private fun JsonObject.positiveInt(key: String) =
    (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
