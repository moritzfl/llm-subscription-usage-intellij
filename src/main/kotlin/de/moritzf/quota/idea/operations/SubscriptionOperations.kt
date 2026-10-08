package de.moritzf.quota.idea.operations

import com.intellij.mcpserver.projectOrNull
import com.intellij.mcpserver.util.projectDirectory
import com.intellij.mcpserver.util.resolveInProject
import de.moritzf.quota.azure.AzureCohereParseClient
import de.moritzf.quota.azure.AzureDocumentIntelligenceClient
import de.moritzf.quota.azure.AzureOcrClient
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.rethrowIfCancellation
import de.moritzf.quota.idea.kimi.KimiDeviceIdStore
import de.moritzf.quota.idea.mcp.*
import de.moritzf.quota.idea.settings.AccountCapability
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.kimi.KimiVisionClient
import de.moritzf.quota.kimi.KimiWebSearchClient
import de.moritzf.quota.minimax.MiniMaxAudioClient
import de.moritzf.quota.minimax.MiniMaxImageClient
import de.moritzf.quota.minimax.MiniMaxRegion
import de.moritzf.quota.minimax.MiniMaxWebSearchClient
import de.moritzf.quota.mistral.MistralAudioClient
import de.moritzf.quota.mistral.MistralImageClient
import de.moritzf.quota.mistral.MistralOcrClient
import de.moritzf.quota.mistral.MistralVisionClient
import de.moritzf.quota.mistral.MistralWebSearchClient
import de.moritzf.quota.ollama.OllamaVisionClient
import de.moritzf.quota.ollama.OllamaWebSearchClient
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.McpJson
import de.moritzf.quota.supergrok.SuperGrokAudioClient
import de.moritzf.quota.supergrok.SuperGrokDocumentClient
import de.moritzf.quota.supergrok.SuperGrokImagineClient
import de.moritzf.quota.supergrok.SuperGrokWebSearchClient
import de.moritzf.quota.zai.ZaiAudioClient
import de.moritzf.quota.zai.ZaiImageClient
import de.moritzf.quota.zai.ZaiOcrClient
import de.moritzf.quota.zai.ZaiVideoClient
import de.moritzf.quota.zai.ZaiVisionClient
import de.moritzf.quota.zai.ZaiWebSearchClient
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal class SubscriptionOperations(
    val accounts: AccountOperations = AccountOperations(),
    val codexClient: (AccountCapability) -> CodexMcpClient = CodexMcpClient::createDefault,
    val kimiSearchClient: KimiWebSearchClient =
        KimiWebSearchClient.createDefault(KimiDeviceIdStore.get()),
    val zaiSearchClient: ZaiWebSearchClient = ZaiWebSearchClient.createDefault(),
    val miniMaxSearchClient: MiniMaxWebSearchClient = MiniMaxWebSearchClient.createDefault(),
    val miniMaxImageClient: MiniMaxImageClient = MiniMaxImageClient.createDefault(),
    val miniMaxAudioClient: MiniMaxAudioClient = MiniMaxAudioClient.createDefault(),
    val ollamaSearchClient: OllamaWebSearchClient = OllamaWebSearchClient.createDefault(),
    val superGrokSearchClient: SuperGrokWebSearchClient = SuperGrokWebSearchClient.createDefault(),
    val superGrokImagineClient: SuperGrokImagineClient = SuperGrokImagineClient.createDefault(),
    val superGrokAudioClient: SuperGrokAudioClient = SuperGrokAudioClient.createDefault(),
    val superGrokDocumentClient: SuperGrokDocumentClient = SuperGrokDocumentClient.createDefault(),
    val mistralSearchClient: MistralWebSearchClient = MistralWebSearchClient.createDefault(),
    val mistralImageClient: MistralImageClient = MistralImageClient.createDefault(),
    val mistralOcrClient: MistralOcrClient = MistralOcrClient.createDefault(),
    val mistralAudioClient: MistralAudioClient = MistralAudioClient.createDefault(),
    val mistralVisionClient: MistralVisionClient = MistralVisionClient.createDefault(),
    val zaiOcrClient: ZaiOcrClient = ZaiOcrClient.createDefault(),
    val zaiImageClient: ZaiImageClient = ZaiImageClient.createDefault(),
    val zaiAudioClient: ZaiAudioClient = ZaiAudioClient.createDefault(),
    val zaiVideoClient: ZaiVideoClient = ZaiVideoClient.createDefault(),
    val zaiVisionClient: ZaiVisionClient = ZaiVisionClient.createDefault(),
    val ollamaVisionClient: OllamaVisionClient = OllamaVisionClient.createDefault(),
    val kimiVisionClient: KimiVisionClient =
        KimiVisionClient.createDefault(KimiDeviceIdStore.get()),
) {
    val azureOcrClient = AzureOcrClient()
    val azureCohereParseClient = AzureCohereParseClient()
    val azureDocumentIntelligenceClient = AzureDocumentIntelligenceClient()

    fun codexResult(response: CodexMcpClient.CodexMcpResponse): String {
        return response.body
    }

    suspend fun withSuperGrokAuth(
        failureLabel: String,
        capability: AccountCapability = AccountCapability.WEB_SEARCH,
        error: (String) -> String = ::searchError,
        block: suspend (String) -> String,
    ): String =
        try {
            accounts.withSuperGrok(capability, block)
        } catch (failure: Exception) {
            failure.rethrowIfCancellation()
            error(failure.message ?: failureLabel)
        }

    suspend fun withMiniMaxKey(
        failureLabel: String,
        capability: AccountCapability,
        block: suspend (String, MiniMaxRegion) -> String,
    ): String =
        try {
            accounts.withMiniMax(capability, block)
        } catch (failure: Exception) {
            failure.rethrowIfCancellation()
            errorResult(failure.message ?: failureLabel)
        }

    suspend fun resolveOptionalPath(value: String?): Path? {
        val trimmed = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val path = Path.of(trimmed)
        if (path.isAbsolute) return path.normalize()
        val project = currentCoroutineContext().projectOrNull ?: return path.normalize()
        return project.resolveInProject(trimmed, throwWhenOutside = false)
    }

    fun noteSpendRateLimit(accountId: String, statusCode: Int?) {
        if (statusCode == 429) {
            de.moritzf.quota.idea.settings.AccountResolver.markRateLimited(accountId)
        }
    }

    fun resolvedApiKey(
        type: QuotaProviderType,
        capability: AccountCapability,
        load: (String) -> String?,
    ): String? {
        val account =
            try {
                de.moritzf.quota.idea.settings.AccountResolver.resolve(
                    type,
                    capability = capability,
                )
            } catch (_: de.moritzf.quota.idea.settings.AccountResolveException) {
                return null
            }
        return load(account.id)
    }

    suspend fun projectBaseDirectory(): Path? {
        return currentCoroutineContext().projectOrNull?.projectDirectory
    }

    fun searchError(message: String): String {
        val settings = runCatching { QuotaSettingsState.getInstance() }.getOrNull()
        val available = mutableListOf<String>()
        for (descriptor in ProviderCatalog.all) {
            val accounts = settings?.accountsOf(descriptor.type).orEmpty()
            val configured =
                if (accounts.isEmpty()) {
                    descriptor.isWebSearchConfigured()
                } else {
                    accounts.any { descriptor.isWebSearchConfiguredForAccount(it.id) }
                }
            if (configured) {
                available.add(descriptor.type.displayName)
            }
        }
        val hint =
            if (available.isEmpty()) {
                " No search providers are currently configured."
            } else {
                " Currently configured search providers: ${available.joinToString(", ")}."
            }
        return errorResult(message + hint)
    }

    fun extractErrorMessage(body: String): String {
        val root = runCatching {
            JsonSupport.json.parseToJsonElement(body) as? JsonObject
        }
            .getOrNull()
        val message =
            (root?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return message ?: body
    }

    fun errorResult(errorMessage: String): String {
        return McpJson.error(errorMessage)
    }
}
