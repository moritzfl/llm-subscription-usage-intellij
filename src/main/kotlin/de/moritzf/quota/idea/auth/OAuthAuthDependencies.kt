package de.moritzf.quota.idea.auth

interface OAuthCredentialStore {
    fun load(): OAuthCredentials?

    fun save(credentials: OAuthCredentials)

    fun clear()
}

interface OAuthTokenOperations {
    suspend fun exchangeAuthorizationCode(code: String, codeVerifier: String): OAuthCredentials

    suspend fun refreshCredentials(existing: OAuthCredentials): OAuthCredentials
}
