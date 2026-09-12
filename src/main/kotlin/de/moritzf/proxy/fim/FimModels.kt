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
                route == SubscriptionProxyRoute.COMPLETIONS
        }
    }
}
