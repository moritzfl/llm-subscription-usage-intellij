package de.moritzf.quota.openai.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO for OAuth token endpoint responses.
 */
@Serializable
class OAuthTokenResponseDto(
    @SerialName("access_token") var accessToken: String? = null,
    @SerialName("refresh_token") var refreshToken: String? = null,
    @SerialName("id_token") var idToken: String? = null,
    @SerialName("expires_in") var expiresIn: Long = 0,
    @SerialName("error") var error: String? = null,
    @SerialName("error_description") var errorDescription: String? = null,
)
