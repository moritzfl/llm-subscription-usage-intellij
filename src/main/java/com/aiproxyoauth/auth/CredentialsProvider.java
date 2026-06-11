package com.aiproxyoauth.auth;

import java.util.Map;

/**
 * Supplies upstream authentication for the proxy. This is the seam an embedder uses to
 * own credential storage and refresh: the proxy library never refreshes tokens on its
 * own when an embedder injects its own provider — it only asks for headers and, on a
 * 401, asks the provider to refresh.
 *
 * <p>{@link AuthManager} is the default file-based implementation used by the CLI. The
 * IDE plugin supplies an implementation backed by its secure-storage auth service so
 * that token refresh is owned entirely by the preexisting login/refresh logic.
 */
public interface CredentialsProvider {

    /**
     * Returns the headers to attach to an upstream request (typically {@code Authorization}
     * plus account/beta headers). Implementations should return freshly valid credentials,
     * refreshing transparently if needed.
     */
    Map<String, String> getAuthHeaders() throws Exception;

    /**
     * Invoked once after the upstream rejects a request with HTTP 401, before a single
     * retry. Implementations should force their credentials to refresh so the retry uses a
     * new token. The default is a no-op for providers that fully self-manage expiry.
     */
    default void refreshAfterUnauthorized() throws Exception {
        // No-op by default: providers that refresh purely on local expiry need nothing here.
    }
}
