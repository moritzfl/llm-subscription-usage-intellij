package de.moritzf.quota.kimi

import java.net.URI

/**
 * Kimi Code's two services. A login belongs to the region that issued it: its tokens refresh and
 * work only against that region's hosts.
 */
enum class KimiRegion(
    val id: String,
    private val authHost: String,
    private val codingBase: String,
) {
    MAINLAND("mainland-cn", "https://auth.kimi.com", "https://api.kimi.com/coding"),
    GLOBAL("global", "https://auth.kimi.ai", "https://api.kimi.ai/coding");

    val oauthHost: String
        get() = authHost

    val tokenEndpoint: URI
        get() = URI.create("$authHost/api/oauth/token")

    /** OpenAI-compatible base, such as `https://api.kimi.ai/coding/v1`. */
    val apiBase: URI
        get() = URI.create("$codingBase/v1")

    /** Anthropic-compatible base. */
    val anthropicBase: URI
        get() = URI.create(codingBase)

    fun api(path: String): URI = URI.create("$codingBase/v1/${path.trimStart('/')}")

    companion object {
        /** Logins saved before regions existed are mainland logins. */
        fun of(id: String?): KimiRegion = entries.firstOrNull { it.id == id } ?: MAINLAND
    }
}
