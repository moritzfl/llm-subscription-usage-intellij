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

class LoginResult private constructor(@JvmField val success: Boolean, @JvmField val message: String?) {
    companion object {
        @JvmStatic
        fun success(): LoginResult = LoginResult(true, null)

        @JvmStatic
        fun error(message: String): LoginResult = LoginResult(false, message)
    }
}

interface AuthService {
    fun startLoginFlow(callback: (LoginResult) -> Unit, onVerificationUrl: ((String, String) -> Unit)? = null)
    fun isLoggedIn(): Boolean
    fun isLoginInProgress(): Boolean
    fun abortLogin(reason: String? = null): Boolean
    fun clearCredentials()
}
