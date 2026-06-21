package de.moritzf.proxy.subscription

class SubscriptionModelCatalog(
    private val providers: List<SubscriptionProxyProvider>,
) {
    private val providersById = providers.associateBy { it.id }
    private val modelsByLocalId: Map<String, SubscriptionProxyModel>
    val models: List<SubscriptionProxyModel>

    init {
        val collected = providers
            .filter { it.isConfigured() }
            .flatMap { provider -> provider.models() }
        val duplicates = collected.groupBy { it.localId }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "Duplicate advertised proxy model IDs: ${duplicates.sorted().joinToString(", ")}"
        }
        models = collected
        modelsByLocalId = models.associateBy { it.localId }
    }

    fun resolve(localId: String?, route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        val requested = localId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return modelsByLocalId[requested]
            ?: providers.asSequence()
                .filter { it.isConfigured() }
                .mapNotNull { it.fallbackModel(requested, route) }
                .firstOrNull()
    }

    fun providerFor(model: SubscriptionProxyModel): SubscriptionProxyProvider? {
        return providersById[model.providerId]?.takeIf { it.isConfigured() }
    }

    fun defaultModel(route: SubscriptionProxyRoute): SubscriptionProxyModel? {
        val routeModels = models.filter { route in it.supportedRoutes }
        return routeModels.firstOrNull { it.isDefault }
            ?: routeModels.maxByOrNull { it.localId }
    }
}
