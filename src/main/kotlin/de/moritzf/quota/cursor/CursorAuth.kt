package de.moritzf.quota.cursor

data class CursorAuth(
    val accessToken: String,
    val email: String = "",
    val membershipType: String = "",
    val sessionCookie: String? = null,
)
