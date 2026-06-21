package de.moritzf.proxy.subscription

class SubscriptionModelCatalog(
    providers: List<SubscriptionProxyProvider>,
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
        models = collected.sortedBy { it.localId }
        modelsByLocalId = models.associateBy { it.localId }
    }

    fun resolve(localId: String?): SubscriptionProxyModel? {
        return localId?.trim()?.takeIf { it.isNotBlank() }?.let(modelsByLocalId::get)
    }

    fun providerFor(model: SubscriptionProxyModel): SubscriptionProxyProvider? {
        return providersById[model.providerId]?.takeIf { it.isConfigured() }
    }
}
