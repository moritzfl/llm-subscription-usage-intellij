package de.moritzf.quota.idea.action

import de.moritzf.quota.idea.auth.QuotaAuthService
import de.moritzf.quota.idea.kimi.KimiCredentialsStore
import de.moritzf.quota.idea.mcp.CodexMcpClient
import de.moritzf.quota.idea.mcp.VisionProvider
import de.moritzf.quota.idea.mistral.MistralApiKeyStore
import de.moritzf.quota.idea.ollama.OllamaApiKeyStore
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.AccountResolver
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.settings.VisionModelSelection
import de.moritzf.quota.idea.zai.ZaiApiKeyStore
import de.moritzf.quota.kimi.KimiVisionClient
import de.moritzf.quota.mistral.MistralVisionClient
import de.moritzf.quota.ollama.OllamaVisionClient
import de.moritzf.quota.shared.DocumentModels
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.supergrok.SuperGrokDocumentClient
import de.moritzf.quota.supergrok.SuperGrokQuotaException
import de.moritzf.quota.zai.ZaiVisionClient
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * IDE-side entry point for asking a vision model about one image; mirrors the subscription_vision
 * MCP tool.
 */
internal object VisionImageAnalysis {
    fun availableProviders(): List<VisionProvider> {
        val settings = QuotaSettingsState.getInstance()
        return VisionProvider.entries.filter { provider ->
            val type = provider.providerType
            settings.accountsOf(type).any { VisionModelSelection.isEnabledForAccount(it.id) }
        }
    }

    fun analyze(provider: VisionProvider, image: Path, prompt: String, model: String = ""): String {
        val type = provider.providerType
        val account = AccountResolver.resolve(type, capability = AccountCapability.VISION)
        val chosen = VisionModelSelection.forAccount(type, account.id, model)
        if (chosen == DocumentModels.OFF) {
            error("Vision is off. Pick a vision model in settings.")
        }
        val trimmedPrompt = prompt.trim().ifBlank { error("Image prompt is required.") }
        return when (provider) {
            VisionProvider.OPEN_AI -> {
                val auth = QuotaAuthService.getInstance()
                val client =
                    CodexMcpClient(
                        accessTokenProvider = { auth.getAccessTokenBlocking(account.id, type) },
                        accountIdProvider = { auth.getAccountId(account.id, type) },
                        tokenRefresher = { auth.forceRefreshBlocking(account.id, type, it) },
                    )
                val response =
                    client.analyzeImage(localFile = image, prompt = trimmedPrompt, model = chosen)
                if (response.isError) error(responseErrorMessage(response.body)) else response.body
            }

            VisionProvider.SUPERGROK -> {
                val auth = QuotaAuthService.getInstance()
                val token =
                    auth.getAccessTokenBlocking(account.id, type) ?: error("Grok login required.")
                val client = SuperGrokDocumentClient()
                try {
                    client.analyzeImage(
                        token,
                        localFile = image,
                        prompt = trimmedPrompt,
                        model = chosen,
                    )
                } catch (exception: SuperGrokQuotaException) {
                    if (exception.statusCode != 401 && exception.statusCode != 403) throw exception
                    val refreshed =
                        auth.forceRefreshBlocking(account.id, type, token) ?: throw exception
                    client.analyzeImage(
                        refreshed,
                        localFile = image,
                        prompt = trimmedPrompt,
                        model = chosen,
                    )
                }
            }

            VisionProvider.MISTRAL -> {
                val key =
                    MistralApiKeyStore.forAccount(account.id).loadBlocking()
                        ?: error("Mistral API key missing.")
                MistralVisionClient.createDefault()
                    .ask(key, localFile = image, prompt = trimmedPrompt, model = chosen)
            }

            VisionProvider.ZAI -> {
                val key =
                    ZaiApiKeyStore.forAccount(account.id).loadBlocking()
                        ?: error("Z.ai API key missing.")
                ZaiVisionClient.createDefault()
                    .ask(key, localFile = image, prompt = trimmedPrompt, model = chosen)
            }

            VisionProvider.OLLAMA -> {
                val key =
                    OllamaApiKeyStore.forAccount(account.id).loadBlocking()
                        ?: error("Ollama API key missing.")
                OllamaVisionClient.createDefault()
                    .ask(key, localFile = image, prompt = trimmedPrompt, model = chosen)
            }

            VisionProvider.KIMI -> {
                val store = KimiCredentialsStore.forAccount(account.id)
                val credentials = store.loadBlocking()
                if (credentials?.isUsable() != true) error("Kimi login required.")
                val result =
                    KimiVisionClient.createDefault()
                        .ask(credentials, localFile = image, prompt = trimmedPrompt, model = chosen)
                if (result.credentials != credentials) store.save(result.credentials)
                result.answer
            }

            VisionProvider.GITHUB ->
                NativeDocumentConversion.githubVision(account.id, chosen, image, trimmedPrompt)

            VisionProvider.OPEN_CODE ->
                NativeDocumentConversion.openCodeVision(account.id, chosen, image, trimmedPrompt)
        }
    }

    private fun responseErrorMessage(body: String): String {
        val json = runCatching {
            JsonSupport.json.parseToJsonElement(body) as? JsonObject
        }
            .getOrNull()
        val error = json?.get("error")
        val message =
            when (error) {
                is JsonPrimitive -> error.contentOrNull
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }?.takeIf { it.isNotBlank() }
        if (message != null) return message
        (json?.get("detail") as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        return body.trim().takeIf { it.isNotBlank() && !it.startsWith("{") }
            ?: "Codex image analysis failed."
    }
}
