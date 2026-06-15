package de.moritzf.proxy.auth

/**
 * Supplies upstream authentication for the proxy. This is the seam an embedder uses to
 * own credential storage and refresh: the proxy library never refreshes tokens on its
 * own when an embedder injects its own provider. It only asks for headers and, on a
 * 401, asks the provider to refresh.
 *
 * [AuthManager] is the default file-based implementation used by the CLI. The IDE
 * plugin supplies an implementation backed by its secure-storage auth service so that
 * token refresh is owned entirely by the preexisting login/refresh logic.
 */
interface CredentialsProvider {
    /**
     * Returns the headers to attach to an upstream request, typically `Authorization`
     * plus account/version headers. Implementations should return freshly valid
     * credentials, refreshing transparently if needed.
     */
    @Throws(Exception::class)
    fun getAuthHeaders(): Map<String, String>

    /**
     * Invoked once after the upstream rejects a request with HTTP 401. Implementations
     * should force their credentials to refresh so a retry can use a new token.
     *
     * @param rejectedAuthorizationHeader the exact `Authorization` header value the
     * rejected request carried, e.g. `Bearer ...`. Implementations use it to dedupe
     * concurrent refreshes: if their current token already differs, another request
     * refreshed in the meantime and no new refresh is needed.
     * @return true if credentials changed and retrying the request is worthwhile; false
     * when nothing was refreshed. The default is a no-op returning false.
     */
    @Throws(Exception::class)
    fun refreshAfterUnauthorized(rejectedAuthorizationHeader: String?): Boolean = false
}
