package de.moritzf.quota.shared.auth

import kotlinx.serialization.Serializable

/** OAuth token data shared by provider clients and credential persistence adapters. */
@Serializable
class OAuthCredentials(
    var accessToken: String? = null,
    var refreshToken: String? = null,
    var expiresAt: Long = 0,
    var accountId: String? = null,
    var hd: String? = null,
    var personalAccessToken: Boolean = false,
)
