package de.moritzf.quota.idea.auth

import kotlinx.serialization.Serializable

/**
 * DTO for persisted OAuth credentials stored in PasswordSafe.
 */
@Serializable
class OAuthCredentials(
    var accessToken: String? = null,
    var refreshToken: String? = null,
    var expiresAt: Long = 0,
    var accountId: String? = null,
    var hd: String? = null,
)
