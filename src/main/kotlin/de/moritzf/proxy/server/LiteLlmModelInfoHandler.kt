package de.moritzf.proxy.server
import de.moritzf.proxy.model.ModelResolver
class LiteLlmModelInfoHandler(
    private val modelResolver: ModelResolver,
) {
    suspend fun handle(ctx: ProxyCall) {
        try {
            val data = modelResolver.resolveModels().map(::modelInfo)
            JsonHelper.toJsonResponse(ctx, mapOf("data" to data))
        } catch (exception: Exception) {
            val message = exception.message ?: "Failed to load model info."
            JsonHelper.toErrorResponse(ctx, message, 502, "upstream_error")
        }
    }
    private fun modelInfo(id: String): Map<String, Any> {
        val litellmParams = linkedMapOf<String, Any>(
            "model" to id,
        )
        val modelInfo = linkedMapOf<String, Any>(
            "id" to id,
            "mode" to "chat",
            "litellm_provider" to "openai-codex",
            "supports_function_calling" to true,
            "supports_parallel_function_calling" to false,
            "supports_tool_choice" to true,
            "supports_vision" to true,
            "supports_prompt_caching" to true,
            // Context limits of the GPT-5 family behind the Codex backend; clients such as
            // Junie size their prompts from these fields.
            "max_input_tokens" to 272_000,
            "max_output_tokens" to 128_000,
            "max_tokens" to 128_000,
            "input_cost_per_token" to 0.0,
            "output_cost_per_token" to 0.0,
            "cache_read_input_token_cost" to 0.0,
        )
        return linkedMapOf(
            "id" to id,
            "model_name" to id,
            "litellm_params" to litellmParams,
            "model_info" to modelInfo,
        )
    }
}
