package de.moritzf.quota.minimax

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Combines the subscription key's usage windows with what a MiniMax Code login reports. */
object MiniMaxQuotaSources {
    /**
     * The key's reading wins for the plan windows. Without a key, the login's own plan reading is
     * used; the login always adds the Credits balance.
     */
    fun combine(
        keyQuota: MiniMaxQuota?,
        account: MiniMaxCodeAccount?,
        loginRegion: MiniMaxRegion,
        warnings: List<String>,
    ): MiniMaxQuota {
        val notes = warnings.toMutableList()
        val base =
            keyQuota
                ?: account?.tokenPlanJson?.let { body ->
                    try {
                        MiniMaxQuotaClient.parseQuota(body, loginRegion)
                    } catch (exception: MiniMaxQuotaException) {
                        notes += "MiniMax Code: ${exception.message}"
                        null
                    }
                }
                ?: MiniMaxQuota(region = loginRegion, planActive = account?.hasTokenPlan != false)
        account?.tokenPlanError?.let { notes += "MiniMax Code: $it" }
        return base.copy(credits = account?.credits ?: base.credits, warnings = notes)
    }

    /**
     * One named segment per credential, like the Mistral wrapper: `subscription_key` holds the
     * key's `token_plan/remains` response, `minimax_code` the login's membership and, without a
     * key, its plan reading. A failed segment keeps its error next to whatever it did return.
     */
    fun rawResponse(
        keyConfigured: Boolean,
        keyBody: String?,
        keyError: String?,
        account: MiniMaxCodeAccount?,
        accountError: String?,
    ): String {
        val key = buildJsonObject {
            jsonOrRaw(keyBody)?.let { put("token_plan", it) }
            keyError?.takeIf { it.isNotBlank() }?.let { put("error", it) }
        }
        val login = buildJsonObject {
            jsonOrRaw(account?.membershipJson)?.let { put("membership", it) }
            jsonOrRaw(account?.tokenPlanJson)?.let { put("token_plan", it) }
            account?.tokenPlanError?.let { put("token_plan_error", it) }
            accountError?.takeIf { it.isNotBlank() }?.let { put("error", it) }
        }
        return JsonSupport.json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                if (keyConfigured) put("subscription_key", key)
                put("minimax_code", login)
            },
        )
    }

    private fun jsonOrRaw(body: String?): JsonElement? {
        val value = body?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { JsonSupport.json.parseToJsonElement(value) }
            .getOrElse { JsonPrimitive(value) }
    }
}
