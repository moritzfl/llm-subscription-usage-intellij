package de.moritzf.quota.opencode.proxy

import de.moritzf.proxy.util.ProxyVersion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

/** Go rejects inference that omits a session id. Keep the client's id when it sent one. */
internal object OpenCodeRequestHeaders {
    const val SESSION = "x-opencode-session"
    const val USER_AGENT = "User-Agent"

    fun forRequest(incomingSession: String?, body: JsonObject): Map<String, String> {
        return mapOf(
            SESSION to sessionId(incomingSession, body),
            USER_AGENT to "llm-subscription-usage/${ProxyVersion.get()}",
        )
    }

    internal fun sessionId(incomingSession: String?, body: JsonObject): String {
        sanitize(incomingSession)?.let { return it }
        sanitize((body["prompt_cache_key"] as? JsonPrimitive)?.contentOrNull)?.let { return it }
        return "lsu-" + fingerprint(body)
    }

    private fun fingerprint(body: JsonObject): String {
        val basis = when (val messages = body["messages"]) {
            is JsonArray -> messages.firstOrNull()?.toString().orEmpty()
            else -> (body["input"] ?: body["prompt"])?.toString().orEmpty()
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(raw: String?): String? {
        val firstLine = raw?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val cleaned = firstLine.replace(UNSAFE, "").take(MAX_LENGTH)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    private val UNSAFE = Regex("[^A-Za-z0-9._:-]")
    private const val MAX_LENGTH = 128
}
