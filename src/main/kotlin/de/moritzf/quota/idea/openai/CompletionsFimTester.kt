package de.moritzf.quota.idea.openai

import de.moritzf.proxy.fim.CompletionSanitizer
import de.moritzf.proxy.fim.CompletionsConfig
import de.moritzf.proxy.fim.CompletionsGuard
import de.moritzf.proxy.fim.CompletionsRequest
import de.moritzf.proxy.fim.FimModels
import de.moritzf.proxy.fim.FimPromptParser
import de.moritzf.proxy.fim.StreamingCompletionSanitizer
import de.moritzf.proxy.server.BufferedProxyCall
import de.moritzf.proxy.server.CompletionsHandler
import de.moritzf.proxy.server.JsonHelper
import de.moritzf.proxy.subscription.SubscriptionModelCatalog
import de.moritzf.proxy.subscription.SubscriptionProxyRequest
import de.moritzf.proxy.subscription.SubscriptionProxyRoute
import de.moritzf.quota.idea.common.QuotaBundle.message
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class CompletionsFimTestResult(
    val ok: Boolean,
    val status: String,
    val elapsedMs: Long? = null,
    val sample: String? = null,
    val insert: String? = null,
    val assembled: String? = null,
    val detail: String? = null,
)

object CompletionsFimTester {
    const val SAMPLE_PROMPT = "fun add(a: Int, b: Int): Int {\n    return "
    const val SAMPLE_SUFFIX = "\n}\n"
    const val SAMPLE_WITH_CURSOR = "fun add(a: Int, b: Int): Int {\n    return |\n}\n"

    suspend fun test(
        catalog: SubscriptionModelCatalog,
        config: CompletionsConfig,
    ): CompletionsFimTestResult =
        try {
            withTimeout(config.timeoutMillis.milliseconds) { execute(catalog, config) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            CompletionsFimTestResult(
                false,
                message("modelTest.completion.timeout"),
                detail = message("modelTest.completion.timeout.detail"),
            )
        }

    private suspend fun execute(
        catalog: SubscriptionModelCatalog,
        config: CompletionsConfig,
    ): CompletionsFimTestResult {
        val model =
            CompletionsHandler.resolveSelectedModel(catalog, config.modelLocalId)
                ?: error(message("modelTest.completion.unavailable"))
        require(FimModels.isEligible(model)) { message("modelTest.completion.ineligible") }
        val provider =
            checkNotNull(catalog.providerFor(model)) {
                message("modelTest.completion.configureAccount")
            }
        val request =
            CompletionsRequest(
                model.localId,
                SAMPLE_PROMPT,
                SAMPLE_SUFFIX,
                maxTokens = config.maxOutputTokens,
            )
        val fim =
            CompletionsGuard.budget(
                FimPromptParser.parse(request.prompt, request.suffix),
                config.maxPromptChars,
            )
        val maxTokens = CompletionsGuard.clampMaxTokens(request.maxTokens, config)
        val native = FimModels.isNativeFimId(model)
        val route =
            if (native) SubscriptionProxyRoute.COMPLETIONS
            else SubscriptionProxyRoute.CHAT_COMPLETIONS
        val body =
            if (native) CompletionsHandler.nativeBody(model.localId, request, fim, maxTokens)
            else
                CompletionsHandler.chatBody(
                    model.localId,
                    request,
                    fim,
                    maxTokens,
                    priorityTier = config.priorityTier && FimModels.supportsPriorityTier(model),
                    reasoningEffort = FimModels.fimReasoningEffort(model),
                )
        val response = BufferedProxyCall(route.normalizedPath)
        val started = System.nanoTime()
        provider.handle(
            response,
            SubscriptionProxyRequest(route, UUID.randomUUID().toString(), model, body),
        )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        val raw = response.body
        if (response.responseStatus() !in 200..<300) {
            return CompletionsFimTestResult(
                ok = false,
                status = message("modelTest.completion.httpError", response.responseStatus()),
                elapsedMs = elapsedMs,
                detail = raw.take(2000).ifBlank { message("modelTest.completion.emptyError") },
            )
        }
        val text =
            if (native) completionText(raw)
            else {
                val sanitizer =
                    StreamingCompletionSanitizer(
                        fim.prefix,
                        fim.suffix,
                        CompletionSanitizer.effectiveStops(
                            fim.prefix,
                            request.stop,
                            fim.languageHint,
                        ),
                        languageHint = fim.languageHint,
                    )
                if (
                    response.contentType?.contains("text/event-stream", ignoreCase = true) == true
                ) {
                    buildString {
                        for (line in raw.lineSequence()) {
                            if (!line.startsWith("data:")) continue
                            val data = line.substringAfter("data:").trim()
                            if (data == "[DONE]") break
                            CompletionsHandler.chatDeltaContent(data)?.let {
                                append(sanitizer.push(it))
                            }
                        }
                        append(sanitizer.finish())
                    }
                } else
                    sanitizer.push(CompletionsHandler.chatMessageContent(raw)) + sanitizer.finish()
            }
        return formatSuccess(text, elapsedMs, raw)
    }

    internal fun formatSuccess(
        insert: String?,
        elapsedMs: Long,
        raw: String = "",
    ): CompletionsFimTestResult {
        if (insert.isNullOrBlank()) {
            return CompletionsFimTestResult(
                ok = false,
                status = message("modelTest.completion.empty"),
                elapsedMs = elapsedMs,
                sample = SAMPLE_WITH_CURSOR.trimEnd(),
                detail =
                    buildString {
                        append(message("modelTest.completion.empty.detail"))
                        if (raw.isNotBlank()) {
                            append("\n\n")
                            append(raw.take(2000))
                        }
                    },
            )
        }
        val assembled = (SAMPLE_PROMPT + insert + SAMPLE_SUFFIX).trimEnd()
        val expression =
            insert.filterNot(Char::isWhitespace).removeSuffix(";").removeSurrounding("(", ")")
        val usable = expression == "a+b" || expression == "b+a"
        return CompletionsFimTestResult(
            ok = usable,
            status =
                if (usable) message("modelTest.completion.usable")
                else message("modelTest.completion.unexpected"),
            elapsedMs = elapsedMs,
            sample = SAMPLE_WITH_CURSOR.trimEnd(),
            insert = insert,
            assembled = assembled,
            detail = if (usable) null else message("modelTest.completion.unexpected.detail"),
        )
    }

    internal fun completionText(raw: String): String? {
        val root = JsonHelper.parseToJsonElementOrNull(raw) as? JsonObject ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val choice = choices.firstOrNull() as? JsonObject ?: return null
        val text = choice["text"] as? JsonPrimitive
        return text?.contentOrNull
    }
}
