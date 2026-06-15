package de.moritzf.proxy.auth

import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.model.CodexClientVersionResolver
import de.moritzf.proxy.util.JwtParser
import com.fasterxml.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.util.concurrent.locks.ReentrantLock

class AuthManager(
    private val config: ServerConfig,
    private val httpClient: HttpClient,
) : CredentialsProvider {
    private val lock = ReentrantLock()

    @Volatile
    private var current: AuthLoader.AuthResult? = null

    @Throws(Exception::class)
    fun ensureFresh(): AuthLoader.AuthResult {
        lock.lock()
        try {
            // Re-check after acquiring the lock: another thread may have already refreshed.
            val existing = current
            if (existing != null && !isTokenExpiringSoon(existing.accessToken())) {
                return existing
            }
            val loaded = AuthLoader.loadAuthTokens(
                config.oauthFilePath(),
                config.oauthClientId(),
                null, // issuer derived from defaults
                config.oauthTokenUrl(),
                httpClient,
            )
            current = loaded
            return loaded
        } finally {
            lock.unlock()
        }
    }

    /**
     * Drops the cached token and reloads from the auth file. Returns true only when the
     * reload produced a different access token than the rejected one: reloading the same
     * locally-valid-but-upstream-rejected token would make a retry pointless.
     */
    @Throws(Exception::class)
    override fun refreshAfterUnauthorized(rejectedAuthorizationHeader: String?): Boolean {
        lock.lock()
        try {
            current = null
        } finally {
            lock.unlock()
        }
        val refreshed = ensureFresh()
        if (refreshed.accessToken().isEmpty()) {
            return false
        }
        val rejectedToken = stripBearer(rejectedAuthorizationHeader)
        return refreshed.accessToken() != rejectedToken
    }

    @Throws(Exception::class)
    override fun getAuthHeaders(): Map<String, String> {
        var auth = current
        if (auth == null || isTokenExpiringSoon(auth.accessToken())) {
            auth = ensureFresh()
        }
        // Java Map.of() rejected null values. accountId() is safe here because
        // AuthLoader.loadAuthTokens() throws before returning a missing account id.
        return mapOf(
            "Authorization" to "Bearer ${auth.accessToken()}",
            "chatgpt-account-id" to auth.accountId(),
            "version" to CodexClientVersionResolver.resolve(config.codexVersion()),
        )
    }

    @Suppress("unused")
    fun getCurrent(): AuthLoader.AuthResult? = current

    companion object {
        private const val REFRESH_EXPIRY_MARGIN_MS = 5 * 60 * 1000L

        private fun stripBearer(authorizationHeader: String?): String? {
            if (authorizationHeader == null) {
                return null
            }
            val value = authorizationHeader.trim()
            return if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7).trim() else value
        }

        private fun isTokenExpiringSoon(accessToken: String?): Boolean {
            if (accessToken.isNullOrEmpty()) {
                return true
            }
            val claims: JsonNode? = JwtParser.parseClaims(accessToken)
            if (claims != null && claims.has("exp") && claims.get("exp").isNumber) {
                val expiryMs = claims.get("exp").asLong() * 1000
                return expiryMs <= System.currentTimeMillis() + REFRESH_EXPIRY_MARGIN_MS
            }
            return false
        }
    }
}
