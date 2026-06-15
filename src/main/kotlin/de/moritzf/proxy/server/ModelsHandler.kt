package de.moritzf.proxy.server

import de.moritzf.proxy.model.ModelResolver
import io.javalin.http.Context
import io.javalin.http.Handler

class ModelsHandler(
    private val modelResolver: ModelResolver,
) : Handler {
    override fun handle(ctx: Context) {
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
