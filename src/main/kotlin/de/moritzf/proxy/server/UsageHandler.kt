package de.moritzf.proxy.server
import de.moritzf.proxy.usage.UsageTracker
import io.javalin.http.Context
import io.javalin.http.Handler
class UsageHandler(
    private val tracker: UsageTracker,
) : Handler {
    override fun handle(ctx: Context) {
        val isAdmin = ctx.attribute<Boolean>("isAdmin") == true
        val keyName = ctx.attribute<String>("keyName")
        // Admin sees all keys. Regular keys see only their own. Open mode sees all.
        val snapshot = if (isAdmin || keyName == null) {
            tracker.snapshot()
        } else {
            mapOf(keyName to (tracker.snapshot()[keyName] ?: UsageTracker.KeyStats(0, 0)))
        }
        val keys = createArrayNode()
        var totalPrompt = 0L
        var totalCompletion = 0L
        snapshot.forEach { (name, stats) ->
            val keyEntry = createObjectNode()
            keyEntry.put("name", name)
            keyEntry.put("prompt_tokens", stats.promptTokens)
            keyEntry.put("completion_tokens", stats.completionTokens)
            keyEntry.put("total_tokens", stats.totalTokens)
            keys.add(keyEntry)
            totalPrompt += stats.promptTokens
            totalCompletion += stats.completionTokens
        }
        val total = createObjectNode()
        total.put("prompt_tokens", totalPrompt)
        total.put("completion_tokens", totalCompletion)
        total.put("total_tokens", totalPrompt + totalCompletion)
        val root = createObjectNode()
        root.set("keys", keys)
        root.set("total", total)
        JsonHelper.toJsonResponse(ctx, root.build())
    }
}
