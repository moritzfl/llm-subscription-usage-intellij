package de.moritzf.proxy.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * LiteLLM's exhausted key, user, team, or tag budget. Since 1.104 it answers HTTP 422 instead of
 * 429; a plain 422 is a validation error and never matches.
 */
internal object LiteLlmBudgetError {
    fun isBudgetExceeded(status: Int, body: String?): Boolean {
        if (status != 422 && status != 429) return false
        val error =
            (JsonHelper.parseToJsonElementOrNull(body.orEmpty()) as? JsonObject)?.get("error")
                as? JsonObject ?: return false
        if ((error["type"] as? JsonPrimitive)?.contentOrNull == "budget_exceeded") return true
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return message.startsWith("Budget has been exceeded!") ||
            message.startsWith("ExceededBudget:")
    }
}
