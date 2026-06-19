package de.moritzf.proxy.auth
import de.moritzf.proxy.config.ServerConfig
import de.moritzf.proxy.server.hasKey
import de.moritzf.proxy.server.createObjectNode
import de.moritzf.proxy.util.Json
import de.moritzf.proxy.util.JwtParser
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
object AuthLoader {
    private const val REFRESH_EXPIRY_MARGIN_MS = 5 * 60 * 1000L
    private const val REFRESH_INTERVAL_MS = 55 * 60 * 1000L
    private val PRETTY_JSON = kotlinx.serialization.json.Json { prettyPrint = true }
    class AuthResult(
        val accessToken: String,
        val accountId: String,
        val idToken: String?,
        val refreshToken: String?,
        val sourcePath: String,
        val lastRefresh: String?,
    )
    fun loadAuthTokens(
        authFilePath: String?,
        clientId: String?,
        issuer: String?,
        tokenUrl: String?,
        httpClient: HttpClient,
    ): AuthResult {
        var resolvedClientId = clientId
        if (resolvedClientId.isNullOrEmpty()) {
            val envClientId = System.getenv("CHATGPT_LOCAL_CLIENT_ID")
            resolvedClientId = if (!envClientId.isNullOrEmpty()) envClientId else ServerConfig.DEFAULT_CLIENT_ID
        }
        var resolvedIssuer = issuer
        if (resolvedIssuer.isNullOrEmpty()) {
            val envIssuer = System.getenv("CHATGPT_LOCAL_ISSUER")
            resolvedIssuer = if (!envIssuer.isNullOrEmpty()) envIssuer else ServerConfig.DEFAULT_ISSUER
        }
        val candidates = AuthFileResolver.resolveCandidates(authFilePath)
        var foundPath: String? = null
        var authData: JsonObject? = null
        for (candidate in candidates) {
            try {
                val path = Path.of(candidate)
                if (Files.exists(path)) {
                    val parsed = Json.INSTANCE.parseToJsonElement(Files.readString(path)) as? JsonObject
                    if (parsed != null) {
                        foundPath = candidate
                        authData = parsed
                        break
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (authData == null) {
            authData = JsonObject(emptyMap())
        }
        val tokensNode = authData["tokens"] as? JsonObject
        var accessToken = getStringField(tokensNode, "access_token")
        var idToken = getStringField(tokensNode, "id_token")
        var refreshToken = getStringField(tokensNode, "refresh_token")
        var accountId = getStringField(tokensNode, "account_id")
        var lastRefresh = getStringField(authData, "last_refresh")
        if (accountId.isNullOrEmpty()) {
            accountId = JwtParser.deriveAccountId(idToken)
        }
        val needsRefresh = !refreshToken.isNullOrEmpty() && shouldRefreshAccessToken(accessToken, lastRefresh)
        if (needsRefresh) {
            var resolvedTokenUrl = tokenUrl
            if (resolvedTokenUrl.isNullOrEmpty()) {
                resolvedTokenUrl = resolvedIssuer.replace(Regex("/$"), "") + "/oauth/token"
            }
            val refreshed = refreshChatGptTokens(refreshToken, resolvedClientId, resolvedTokenUrl, httpClient)
            if (refreshed == null) {
                System.err.println(
                    "Warning: OAuth token refresh failed (server returned error). Continuing with existing token.",
                )
            } else {
                accessToken = refreshed.accessToken
                if (refreshed.idToken != null) idToken = refreshed.idToken
                if (refreshed.refreshToken != null) refreshToken = refreshed.refreshToken
                if (refreshed.accountId != null) accountId = refreshed.accountId
                lastRefresh = Instant.now().toString()
                val writePath = AuthFileResolver.resolveWritePath(foundPath ?: authFilePath)
                writeAuthFile(writePath, authData, idToken, accessToken, refreshToken, accountId, lastRefresh)
            }
        }
        if (accessToken.isNullOrEmpty()) {
            throw IOException("ChatGPT access token not found. Run `codex login` to create auth.json.")
        }
        if (accountId.isNullOrEmpty()) {
            throw IOException("ChatGPT account id not found in auth.json. Run `codex login` to create auth.json.")
        }
        val finalAccessToken = accessToken
        val finalAccountId = accountId
        val sourcePath = foundPath ?: AuthFileResolver.resolveWritePath(authFilePath)
        return AuthResult(finalAccessToken, finalAccountId, idToken, refreshToken, sourcePath, lastRefresh)
    }
    private fun shouldRefreshAccessToken(accessToken: String?, lastRefresh: String?): Boolean {
        if (accessToken.isNullOrEmpty()) {
            return true
        }
        val claims = JwtParser.parseClaims(accessToken)
        if (claims != null && claims.hasKey("exp")) {
            val exp = claims["exp"]
            if (exp is JsonPrimitive) {
                val expValue = exp.longOrNull
                if (expValue != null) {
                    val expiryMs = expValue * 1000
                    if (expiryMs <= System.currentTimeMillis() + REFRESH_EXPIRY_MARGIN_MS) {
                        return true
                    }
                }
            }
        }
        if (!lastRefresh.isNullOrEmpty()) {
            try {
                val refreshedAt = Instant.parse(lastRefresh)
                return refreshedAt.toEpochMilli() <= System.currentTimeMillis() - REFRESH_INTERVAL_MS
            } catch (_: Exception) {
            }
        }
        return false
    }
    private data class RefreshResult(
        val accessToken: String,
        val idToken: String?,
        val refreshToken: String?,
        val accountId: String?,
    )
    private fun refreshChatGptTokens(
        refreshToken: String,
        clientId: String,
        tokenUrl: String,
        httpClient: HttpClient,
    ): RefreshResult? {
        val body = createObjectNode()
        body.put("grant_type", "refresh_token")
        body.put("refresh_token", refreshToken)
        body.put("client_id", clientId)
        body.put("scope", "openid profile email offline_access")
        val request = HttpRequest.newBuilder()
            .uri(URI.create(tokenUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.INSTANCE.encodeToString(JsonObject.serializer(), body.build())))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..<300) {
            return null
        }
        val payload = Json.INSTANCE.parseToJsonElement(response.body()) as? JsonObject
            ?: return null
        val newAccessToken = getStringField(payload, "access_token")
        if (newAccessToken.isNullOrEmpty()) {
            return null
        }
        val newIdToken = getStringField(payload, "id_token")
        var newRefreshToken = getStringField(payload, "refresh_token")
        if (newRefreshToken.isNullOrEmpty()) {
            newRefreshToken = refreshToken
        }
        return RefreshResult(
            newAccessToken,
            newIdToken,
            newRefreshToken,
            JwtParser.deriveAccountId(newIdToken),
        )
    }
    private fun writeAuthFile(
        filePath: String,
        originalData: JsonObject,
        idToken: String?,
        accessToken: String?,
        refreshToken: String?,
        accountId: String?,
        lastRefresh: String?,
    ) {
        try {
            val root = de.moritzf.proxy.server.MutableJsonObject(originalData)
            val tokens = createObjectNode()
            if (idToken != null) tokens.put("id_token", idToken)
            if (accessToken != null) tokens.put("access_token", accessToken)
            if (refreshToken != null) tokens.put("refresh_token", refreshToken)
            if (accountId != null) tokens.put("account_id", accountId)
            root.set("tokens", tokens)
            root.put("last_refresh", lastRefresh)
            val path = Path.of(filePath)
            val parent = path.parent
            if (parent != null) Files.createDirectories(parent)
            // Write to a sibling temp file first, then rename atomically to avoid
            // leaving a truncated auth.json if the JVM crashes mid-write.
            val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
            // Set strict permissions BEFORE writing any content.
            setStrictFilePermissions(tmp)
            Files.writeString(tmp, PRETTY_JSON.encodeToString(JsonObject.serializer(), root.build()))
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (exception: Exception) {
            System.err.println("Warning: failed to write auth file to $filePath: $exception")
        }
    }
    private fun setStrictFilePermissions(path: Path) {
        try {
            if (!Files.exists(path)) {
                Files.createFile(path)
            }
            val supportedViews = FileSystems.getDefault().supportedFileAttributeViews()
            if (supportedViews.contains("posix")) {
                // POSIX (Linux, macOS): chmod 600.
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                )
            } else if (supportedViews.contains("acl")) {
                // Windows: ACLs.
                val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java)
                val owner = Files.getOwner(path)
                val entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(owner)
                    .setPermissions(
                        AclEntryPermission.READ_DATA,
                        AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA,
                        AclEntryPermission.READ_NAMED_ATTRS,
                        AclEntryPermission.WRITE_NAMED_ATTRS,
                        AclEntryPermission.READ_ATTRIBUTES,
                        AclEntryPermission.WRITE_ATTRIBUTES,
                        AclEntryPermission.READ_ACL,
                        AclEntryPermission.WRITE_ACL,
                        AclEntryPermission.WRITE_OWNER,
                        AclEntryPermission.SYNCHRONIZE,
                        AclEntryPermission.DELETE,
                    )
                    .build()
                // Set the owner-only ACL.
                view.acl = listOf(entry)
            }
        } catch (exception: Exception) {
            System.err.println("Warning: could not set strict file permissions on $path: ${exception.message}")
        }
    }
    private fun getStringField(node: JsonObject?, field: String): String? {
        if (node == null || !node.hasKey(field)) {
            return null
        }
        val value = node[field]
        return if (value is JsonPrimitive && value.isString && value.content.isNotEmpty()) value.content else null
    }
}