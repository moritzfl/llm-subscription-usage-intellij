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
import de.moritzf.quota.ollama.OllamaQuotaException
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.zai.ZaiQuotaException

internal suspend fun SubscriptionOperations.subscription_vision(
    prompt: String,
    provider: VisionProvider = VisionProvider.OPEN_AI,
    imageUrl: String? = null,
    localFile: String? = null,
    model: String = "",
): String {
    val chosen = model.trim().ifBlank { visionModel(provider) }
    if (chosen == "-" || chosen.isBlank()) {
        return errorResult(
            "Vision is off for ${provider.providerType.displayName}. Pick a vision model in settings, or pass model="
        )
    }
    val trimmedPrompt = prompt.trim()
    if (trimmedPrompt.isBlank()) {
        return errorResult("Image prompt is required.")
    }
    return when (provider) {
        VisionProvider.OPEN_AI -> {
            val response =
                codexClient(AccountCapability.VISION)
                    .analyzeImage(
                        imageUrl,
                        resolveOptionalPath(localFile),
                        trimmedPrompt,
                        chosen,
                    )
            if (response.isError) response.body
            else McpJson.visionResult(provider.name, chosen, response.body)
        }

        VisionProvider.SUPERGROK ->
            withSuperGrokAuth(
                "Grok image analysis failed.",
                AccountCapability.VISION,
                error = ::errorResult,
            ) { accessToken ->
                McpJson.visionResult(
                    provider.name,
                    chosen,
                    superGrokDocumentClient.analyzeImage(
                        accessToken,
                        imageUrl,
                        resolveOptionalPath(localFile),
                        trimmedPrompt,
                        chosen,
                    ),
                )
            }

        VisionProvider.MISTRAL -> mistralVision(trimmedPrompt, imageUrl, localFile, chosen)

        VisionProvider.ZAI -> zaiVision(trimmedPrompt, imageUrl, localFile, chosen)

        VisionProvider.OLLAMA -> ollamaVision(trimmedPrompt, imageUrl, localFile, chosen)

        VisionProvider.GITHUB -> githubVision(trimmedPrompt, localFile, chosen)

        VisionProvider.OPEN_CODE -> openCodeVision(trimmedPrompt, localFile, chosen)

        VisionProvider.KIMI -> kimiVision(trimmedPrompt, imageUrl, localFile, chosen)
    }
}

internal fun SubscriptionOperations.visionModel(provider: VisionProvider): String {
    val accountId =
        runCatching {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    provider.providerType,
                    capability = AccountCapability.VISION,
                )
                .id
        }
            .getOrNull() ?: return ""
    return de.moritzf.quota.idea.settings.VisionModelSelection.forAccount(
        provider.providerType,
        accountId,
    )
}

internal suspend fun SubscriptionOperations.mistralVision(
    prompt: String,
    imageUrl: String?,
    localFile: String?,
    model: String,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.MISTRAL, AccountCapability.VISION) {
            MistralApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Mistral API key missing. Add a Mistral API key in settings.")
    }
    return try {
        val answer =
            mistralVisionClient.ask(
                apiKey,
                imageUrl,
                resolveOptionalPath(localFile),
                prompt,
                model,
            )
        McpJson.visionResult(VisionProvider.MISTRAL.name, model, answer)
    } catch (exception: MistralQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral image analysis failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Mistral image analysis failed.")
    }
}

internal suspend fun SubscriptionOperations.zaiVision(
    prompt: String,
    imageUrl: String?,
    localFile: String?,
    model: String,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.ZAI, AccountCapability.VISION) {
            ZaiApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Z.ai API key missing. Add a Z.ai API key in settings.")
    }
    return try {
        val answer =
            zaiVisionClient.ask(apiKey, imageUrl, resolveOptionalPath(localFile), prompt, model)
        McpJson.visionResult(VisionProvider.ZAI.name, model, answer)
    } catch (exception: ZaiQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai image analysis failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Z.ai image analysis failed.")
    }
}

internal suspend fun SubscriptionOperations.ollamaVision(
    prompt: String,
    imageUrl: String?,
    localFile: String?,
    model: String,
): String {
    val apiKey =
        resolvedApiKey(QuotaProviderType.OLLAMA, AccountCapability.VISION) {
            OllamaApiKeyStore.forAccount(it).loadBlocking()
        }
    if (apiKey.isNullOrBlank()) {
        return errorResult("Ollama API key missing. Add an Ollama API key in settings.")
    }
    return try {
        val answer =
            ollamaVisionClient.ask(
                apiKey,
                imageUrl,
                resolveOptionalPath(localFile),
                prompt,
                model,
            )
        McpJson.visionResult(VisionProvider.OLLAMA.name, model, answer)
    } catch (exception: OllamaQuotaException) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Ollama image analysis failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Ollama image analysis failed.")
    }
}

internal suspend fun SubscriptionOperations.kimiVision(
    prompt: String,
    imageUrl: String?,
    localFile: String?,
    model: String,
): String {
    val account =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                QuotaProviderType.KIMI,
                capability = AccountCapability.VISION,
            )
        } catch (exception: de.moritzf.quota.idea.settings.AccountResolveException) {
            exception.rethrowIfCancellation()
            return errorResult(exception.message ?: "Kimi login required. Log in from settings.")
        }
    val store = KimiCredentialsStore.forAccount(account.id)
    val credentials = store.loadBlocking()
    if (credentials?.isUsable() != true) {
        return errorResult("Kimi login required. Log in from settings.")
    }
    return try {
        val result =
            kimiVisionClient.ask(
                credentials,
                imageUrl,
                resolveOptionalPath(localFile),
                prompt,
                model,
            )
        if (result.credentials != credentials) {
            store.save(result.credentials)
        }
        McpJson.visionResult(VisionProvider.KIMI.name, model, result.answer)
    } catch (exception: KimiQuotaException) {
        exception.rethrowIfCancellation()
        noteSpendRateLimit(account.id, exception.statusCode)
        errorResult(exception.message ?: "Kimi image analysis failed.")
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "Kimi image analysis failed.")
    }
}

internal suspend fun SubscriptionOperations.githubVision(
    prompt: String,
    localFile: String?,
    model: String,
): String {
    val source =
        resolveOptionalPath(localFile)
            ?: return errorResult("GitHub Copilot vision needs a local image file in localFile.")
    val account =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    QuotaProviderType.GITHUB,
                    capability = AccountCapability.VISION,
                )
                .id
        } catch (_: de.moritzf.quota.idea.settings.AccountResolveException) {
            return errorResult("Sign in to GitHub Copilot in settings.")
        }
    return try {
        val answer =
            de.moritzf.quota.idea.action.NativeDocumentConversion.githubVision(
                account,
                model,
                source,
                prompt,
            )
        McpJson.visionResult(VisionProvider.GITHUB.name, model, answer)
    } catch (exception: kotlinx.coroutines.CancellationException) {
        exception.rethrowIfCancellation()
        throw exception
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "GitHub Copilot image analysis failed.")
    }
}

internal suspend fun SubscriptionOperations.openCodeVision(
    prompt: String,
    localFile: String?,
    model: String,
): String {
    val source =
        resolveOptionalPath(localFile)
            ?: return errorResult("OpenCode vision needs a local image file in localFile.")
    val account =
        try {
            de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    QuotaProviderType.OPEN_CODE,
                    capability = AccountCapability.VISION,
                )
                .id
        } catch (_: de.moritzf.quota.idea.settings.AccountResolveException) {
            return errorResult("Sign in to OpenCode in settings.")
        }
    return try {
        val answer =
            de.moritzf.quota.idea.action.NativeDocumentConversion.openCodeVision(
                account,
                model,
                source,
                prompt,
            )
        McpJson.visionResult(VisionProvider.OPEN_CODE.name, model, answer)
    } catch (exception: kotlinx.coroutines.CancellationException) {
        exception.rethrowIfCancellation()
        throw exception
    } catch (exception: Exception) {
        exception.rethrowIfCancellation()
        errorResult(exception.message ?: "OpenCode image analysis failed.")
    }
}
