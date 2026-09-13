package de.moritzf.proxy.usage

import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.ProxyCallAttributes
import de.moritzf.proxy.server.longPath
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object UsageJson {
    fun record(ctx: ProxyCall, body: String) {
        val root = JsonHelper.parseToJsonElementOrNull(body) as? JsonObject ?: return
        record(ctx, root["usage"])
    }

    fun record(ctx: ProxyCall, usageNode: JsonElement?) {
        val tracker = ctx.getAttribute(ProxyCallAttributes.USAGE_TRACKER) ?: return
        val tokens = tokensFrom(usageNode) ?: return
        tracker.record(ctx.getAttribute(ProxyCallAttributes.KEY_NAME), tokens.first, tokens.second)
    }

    fun tokensFrom(usageNode: JsonElement?): Pair<Long, Long>? {
        val usage = usageNode as? JsonObject ?: return null
        val prompt = firstPresent(usage, "prompt_tokens", "input_tokens")
        val completion = firstPresent(usage, "completion_tokens", "output_tokens")
        if (prompt == 0L && completion == 0L) return null
        return prompt to completion
    }

    private fun firstPresent(usage: JsonObject, vararg keys: String): Long {
        for (key in keys) {
            if (key in usage) return usage.longPath(key, 0L)
        }
        return 0L
    }
}
