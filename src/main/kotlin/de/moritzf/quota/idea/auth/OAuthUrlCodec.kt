package de.moritzf.quota.idea.auth

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * URL encoding and parsing helpers shared across OAuth components.
 */
object OAuthUrlCodec {
    @JvmStatic
    fun formEncode(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8)}=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
    }

    @JvmStatic
    fun formEncode(vararg params: Pair<String, String>): String = formEncode(linkedMapOf(*params))

    @JvmStatic
    fun parseQuery(query: String?): Map<String, String> {
        val params = linkedMapOf<String, String>()
        if (query.isNullOrBlank()) {
            return params
        }

        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) {
                continue
            }
            val key = URLDecoder.decode(pair.substring(0, idx), Charsets.UTF_8)
            val value = URLDecoder.decode(pair.substring(idx + 1), Charsets.UTF_8)
            params[key] = value
        }
        return params
    }

    @JvmStatic
    fun parseCallbackUri(value: String?, redirectUri: String): URI {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return URI.create("")
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return URI.create(trimmed)
        }
        if (trimmed.startsWith("/auth/callback")) {
            return URI.create(redirectUri + trimmed.removePrefix("/auth/callback"))
        }
        return URI.create("$redirectUri?$trimmed")
    }
}
