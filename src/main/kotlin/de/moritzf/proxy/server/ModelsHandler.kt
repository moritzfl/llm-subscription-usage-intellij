package de.moritzf.proxy.server

import de.moritzf.proxy.model.ChatGptSubscriptionModels
import de.moritzf.proxy.model.ModelResolver
import de.moritzf.proxy.model.ReasoningCapabilities

class ModelsHandler(private val modelResolver: ModelResolver) {
    suspend fun handle(ctx: ProxyCall) {
        try {
            val data =
                modelResolver.resolveModels().map { id ->
                    buildMap<String, Any> {
                        put("id", id)
                        put("object", "model")
                        put("created", 0)
                        put("owned_by", "codex-oauth")
                        ChatGptSubscriptionModels.reasoningEfforts(id)
                            ?.let(ReasoningCapabilities::openRouterField)
                            ?.let { put("reasoning", it) }
                    }
                }
            JsonHelper.toJsonResponse(ctx, mapOf("object" to "list", "data" to data))
        } catch (exception: Exception) {
            val message = exception.message ?: "Failed to load models."
            JsonHelper.toErrorResponse(ctx, message, 502, "upstream_error")
        }
    }
}
