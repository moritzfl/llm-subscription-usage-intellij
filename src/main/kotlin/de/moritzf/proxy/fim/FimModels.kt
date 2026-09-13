package de.moritzf.proxy.fim

import de.moritzf.proxy.subscription.SubscriptionProxyModel
import de.moritzf.proxy.subscription.SubscriptionProxyRoute

object FimModels {
    fun isEligible(model: SubscriptionProxyModel): Boolean {
        if (model.supportedRoutes.all { it == SubscriptionProxyRoute.ANTHROPIC_MESSAGES }) return false
        val blob = "${model.localId} ${model.upstreamId} ${model.providerId}"
        if (blob.contains("claude", ignoreCase = true)) return false
        if (blob.contains("cursor", ignoreCase = true)) return false
        return model.supportedRoutes.any { route ->
            route == SubscriptionProxyRoute.CHAT_COMPLETIONS ||
                route == SubscriptionProxyRoute.RESPONSES ||
                route == SubscriptionProxyRoute.COMPLETIONS ||
                route == SubscriptionProxyRoute.FIM_COMPLETIONS
        }
    }

    fun isNativeFimId(model: SubscriptionProxyModel): Boolean {
        return isNativeFimId(model.localId) || isNativeFimId(model.upstreamId)
    }

    fun isNativeFimId(id: String): Boolean {
        val n = id.trim().lowercase()
        if (n.isEmpty()) return false
        if (NON_FIM_MARKERS.any { it in n }) return false
        return "fim" in n || "codestral" in n
    }

    fun supportsPriorityTier(model: SubscriptionProxyModel): Boolean {
        return supportsPriorityTier(model.localId, model.providerId)
    }

    fun supportsPriorityTier(localId: String, providerId: String = ""): Boolean {
        val provider = providerId.trim().lowercase()
        if (provider == "supergrok" || provider == "openai") return true
        val id = localId.trim().lowercase()
        return id.startsWith("sg-") || id.startsWith("oa-")
    }

    private val NON_FIM_MARKERS = listOf(
        "embed",
        "ocr",
        "moderation",
        "tts",
        "transcribe",
        "voxtral",
        "imagine",
    )
}
