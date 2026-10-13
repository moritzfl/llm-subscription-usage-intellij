package de.moritzf.quota.github.proxy

import de.moritzf.quota.github.GitHubQuotaClient
import de.moritzf.quota.github.GitHubQuotaException
import de.moritzf.quota.shared.JsonSupport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Account-bound, in-memory Copilot session. The durable GitHub login stays in Password Safe. */
internal class GitHubCopilotSessionClient(
    private val accessTokenProvider: () -> String?,
    private val httpClient: HttpClient,
    private val endpoint: URI,
    private val clock: Clock = Clock.systemUTC(),
) {
    private var cached: CachedSession? = null

    /** Coalesces expiry/401 refreshes and never reuses a session after the GitHub login changes. */
    @Synchronized
    fun session(rejectedToken: String? = null): Session {
        val githubToken = accessTokenProvider().trimmedOrNull()
        if (githubToken == null) {
            cached = null
            throw GitHubQuotaException("GitHub Copilot login required.", 401)
        }
        val now = clock.instant().epochSecond
        cached?.let {
            if (
                it.githubToken == githubToken &&
                    it.refreshAt > now &&
                    it.session.token != rejectedToken
            ) {
                return it.session
            }
        }
        cached = null
        val request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
        githubCopilotHeaders(githubToken).forEach { (name, value) -> request.header(name, value) }
        request.setHeader("Authorization", "token $githubToken")
        val response = httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            val loginRejected = response.statusCode() in setOf(401, 403, 404)
            throw GitHubQuotaException(
                if (loginRejected)
                    "Copilot session authorization failed. Log out and log in to GitHub Copilot in this plugin's settings to enable native FIM."
                else "Copilot session request failed (HTTP ${response.statusCode()}).",
                if (loginRejected) 401 else response.statusCode(),
            )
        }
        // Never attach the token response or decoder exception to diagnostics.
        val dto =
            try {
                JsonSupport.json.decodeFromString<SessionDto>(response.body())
            } catch (_: Exception) {
                throw GitHubQuotaException("Copilot returned an invalid session response.", 502)
            }
        val token = dto.token?.takeIf { it.isNotBlank() }
        val expiresAt = dto.expiresAt
        if (token == null || expiresAt == null || expiresAt <= now) {
            throw GitHubQuotaException("Copilot returned a missing or expired session token.", 502)
        }
        val proxy = runCatching { URI.create(dto.endpoints?.proxy ?: DEFAULT_PROXY) }.getOrNull()
        if (
            proxy == null ||
                proxy.host == null ||
                (proxy.scheme != "https" &&
                    !(proxy.scheme == "http" && proxy.host in setOf("127.0.0.1", "[::1]"))) ||
                proxy.userInfo != null ||
                proxy.query != null ||
                proxy.fragment != null
        ) {
            throw GitHubQuotaException("Copilot returned an invalid completion endpoint.", 502)
        }
        val session = Session(token, proxy)
        val refreshAt =
            minOf(
                expiresAt - minOf(60L, (expiresAt - now) / 2),
                dto.refreshIn?.takeIf { it > 0 }?.let { now + it } ?: expiresAt,
            )
        cached = CachedSession(githubToken, session, refreshAt)
        return session
    }

    class Session(val token: String, val proxy: URI)

    private class CachedSession(val githubToken: String, val session: Session, val refreshAt: Long)

    @Serializable
    private data class SessionDto(
        val token: String? = null,
        @SerialName("expires_at") val expiresAt: Long? = null,
        @SerialName("refresh_in") val refreshIn: Long? = null,
        val endpoints: EndpointsDto? = null,
    )

    @Serializable private data class EndpointsDto(val proxy: String? = null)

    companion object {
        private const val DEFAULT_PROXY = "https://copilot-proxy.githubusercontent.com"

        fun endpoint(enterpriseHost: String?): URI =
            URI.create(
                "https://${GitHubQuotaClient.apiHost(enterpriseHost)}/copilot_internal/v2/token"
            )
    }
}
