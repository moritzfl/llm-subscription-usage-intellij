package de.moritzf.proxy.server
import de.moritzf.proxy.model.ModelResolver
class ModelsHandler(
    private val modelResolver: ModelResolver,
) {
    suspend fun handle(ctx: ProxyCall) {
        try {
            val data = modelResolver.resolveModels().map { id ->
                mapOf(
                    "id" to id,
                    "object" to "model",
                    "created" to 0,
                    "owned_by" to "codex-oauth",
                )
            }
            JsonHelper.toJsonResponse(ctx, mapOf("object" to "list", "data" to data))
        } catch (exception: Exception) {
            val message = exception.message ?: "Failed to load models."
            JsonHelper.toErrorResponse(ctx, message, 502, "upstream_error")
        }
    }
}
