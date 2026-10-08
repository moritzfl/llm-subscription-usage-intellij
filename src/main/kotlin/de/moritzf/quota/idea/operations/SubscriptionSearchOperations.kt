package de.moritzf.quota.idea.operations

import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.mcp.*
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiQuotaException
import de.moritzf.quota.mistral.MistralQuotaException
import de.moritzf.quota.mistral.MistralWebSearchClient
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.supergrok.SuperGrokWebSearchClient
import de.moritzf.quota.zai.ZaiQuotaException

internal suspend fun SubscriptionOperations.codex_web_search(
    query: String,
    searchContextSize: String = "medium",
    includeSources: Boolean = false,
    externalWebAccess: Boolean = true,
    allowedDomains: String? = null,
    blockedDomains: String? = null,
): String {
    val response =
        codexClient(AccountCapability.WEB_SEARCH)
            .webSearch(
                query,
                searchContextSize,
                includeSources,
                externalWebAccess,
                allowedDomains,
                blockedDomains,
            )
    return if (response.isError) searchError(extractErrorMessage(response.body)) else response.body
}

internal suspend fun SubscriptionOperations.supergrok_web_search(
    query: String,
    model: String = SuperGrokWebSearchClient.DEFAULT_MODEL,
    allowedDomains: String? = null,
    excludedDomains: String? = null,
    maxOutputTokens: Int = SuperGrokWebSearchClient.DEFAULT_MAX_OUTPUT_TOKENS,
): String {
    return supergrokWebSearch(query, model, allowedDomains, excludedDomains, maxOutputTokens)
}

internal suspend fun SubscriptionOperations.subscription_web_search(
    provider: ListSearchProvider = ListSearchProvider.KIMI,
    query: String,
    limit: Int = 5,
    includeContent: Boolean = false,
): String {
    return when (provider) {
        ListSearchProvider.KIMI -> kimiWebSearch(query, limit, includeContent)
        ListSearchProvider.ZAI -> zaiWebSearch(query, limit, includeContent)
        ListSearchProvider.MINIMAX -> miniMaxWebSearch(query, limit, includeContent)
        ListSearchProvider.OLLAMA -> ollamaWebSearch(query, limit, includeContent)
    }
}

internal suspend fun SubscriptionOperations.subscription_web_fetch(
    provider: WebFetchProvider = WebFetchProvider.OLLAMA,
    url: String,
): String {
    return when (provider) {
        WebFetchProvider.OLLAMA -> ollamaWebFetch(url)
        WebFetchProvider.ZAI -> zaiWebFetch(url)
    }
}

internal suspend fun SubscriptionOperations.mistral_web_search(
    query: String,
    model: String = MistralWebSearchClient.DEFAULT_MODEL,
    premium: Boolean = false,
): String {
    return mistralWebSearch(query, model, premium)
}

internal suspend fun SubscriptionOperations.supergrokWebSearch(
    query: String,
    model: String,
    allowedDomains: String?,
    blockedDomains: String?,
    maxOutputTokens: Int,
): String {
    return withSuperGrokAuth("Grok web search failed.") { accessToken ->
        superGrokSearchClient.webSearch(
            accessToken,
            query,
            model,
            allowedDomains,
            blockedDomains,
            maxOutputTokens,
        )
    }
}

internal suspend fun SubscriptionOperations.kimiWebSearch(
    query: String,
    limit: Int,
    includeContent: Boolean,
): String {
    val account =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                QuotaProviderType.KIMI,
                capability = de.moritzf.quota.idea.settings.AccountCapability.WEB_SEARCH,
            )
        } catch (exception: de.moritzf.quota.idea.settings.AccountResolveException) {
            exception.rethrowIfCancellation()
            return searchError(exception.message ?: "Kimi login required. Log in from settings.")
        }
    val store = KimiCredentialsStore.forAccount(account.id)
    val credentials = store.loadBlocking()
    if (credentials?.isUsable() != true) {
        return searchError("Kimi login required. Log in from settings.")
    }
    return try {
        val result = kimiSearchClient.webSearch(credentials, query, limit, includeContent)
        if (result.credentials != credentials) {
            store.save(result.credentials)
        }
        result.body
    } catch (exception: KimiQuotaException) {
        exception.rethrowIfCancellation()
        noteSpendRateLimit(account.id, exception.statusCode)
        searchError(exception.message ?: "Kimi web search failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Kimi web search failed.")
    }
}

internal suspend fun SubscriptionOperations.zaiWebSearch(
    query: String,
    limit: Int,
    includeContent: Boolean,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.WEB_SEARCH) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return searchError("Z.ai API key missing. Add a Z.ai API key in settings.")
    }
    return try {
        zaiSearchClient.webSearch(apiKey, query, limit, includeContent)
    } catch (exception: ZaiQuotaException) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Z.ai web search failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Z.ai web search failed.")
    }
}

internal suspend fun SubscriptionOperations.miniMaxWebSearch(
    query: String,
    limit: Int,
    includeContent: Boolean,
): String {
    return withMiniMaxKey("MiniMax web search failed.", AccountCapability.WEB_SEARCH) { key, region
        ->
        miniMaxSearchClient.webSearch(key, region, query, limit, includeContent)
    }
}

internal suspend fun SubscriptionOperations.mistralWebSearch(
    query: String,
    model: String,
    premium: Boolean,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.MISTRAL, AccountCapability.WEB_SEARCH) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return searchError("Mistral API key missing. Add a Mistral API key in settings.")
    }
    return try {
        mistralSearchClient.webSearch(apiKey, query, model, premium)
    } catch (exception: MistralQuotaException) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Mistral web search failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Mistral web search failed.")
    }
}

internal fun SubscriptionOperations.ollamaWebFetch(url: String): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.OLLAMA, AccountCapability.WEB_SEARCH) {
            OllamaApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Ollama API key missing. Add an Ollama API key in settings.")
    }
    return try {
        ollamaSearchClient.webFetch(apiKey, url)
    } catch (exception: OllamaQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Ollama web fetch failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Ollama web fetch failed.")
    }
}

internal fun SubscriptionOperations.zaiWebFetch(url: String): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.WEB_SEARCH) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
    }
    return try {
        zaiSearchClient.webFetch(apiKey, url)
    } catch (exception: ZaiQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai web fetch failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai web fetch failed.")
    }
}

internal suspend fun SubscriptionOperations.ollamaWebSearch(
    query: String,
    limit: Int,
    includeContent: Boolean,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.OLLAMA, AccountCapability.WEB_SEARCH) {
            OllamaApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return searchError("Ollama API key missing. Add an Ollama API key in settings.")
    }
    return try {
        ollamaSearchClient.webSearch(apiKey, query, limit, includeContent)
    } catch (exception: OllamaQuotaException) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Ollama web search failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        searchError(exception.message ?: "Ollama web search failed.")
    }
}
