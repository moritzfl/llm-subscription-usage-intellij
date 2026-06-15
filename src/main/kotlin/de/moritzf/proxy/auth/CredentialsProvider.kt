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
    fun getAuthHeaders(): Map<String, String>

    fun refreshAfterUnauthorized(rejectedAuthorizationHeader: String?): Boolean = false
}
