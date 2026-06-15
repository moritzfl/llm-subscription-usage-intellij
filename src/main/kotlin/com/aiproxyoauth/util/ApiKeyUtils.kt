package com.aiproxyoauth.util

import com.aiproxyoauth.config.ServerConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import java.util.HexFormat

object ApiKeyUtils {
    /** Parses "name:key" or bare "key" into the map (key -> name). */
    @JvmStatic
    fun parseKeyEntry(entry: String, map: MutableMap<String, String>) {
        val colon = entry.indexOf(':')
        if (colon >= 0) {
            val name = entry.substring(0, colon).trim()
            val key = entry.substring(colon + 1).trim()
            if (name.isNotEmpty() && key.isNotEmpty()) {
                map[key] = name
            }
        } else {
            val key = entry.trim()
            if (key.isNotEmpty()) {
                map[key] = key
            }
        }
    }

    @JvmStatic
    fun generateNewKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return ServerConfig.KEY_PREFIX + HexFormat.of().formatHex(bytes)
    }

    @JvmStatic
    fun fingerprint(key: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            HexFormat.of().formatHex(digest.digest(key.toByteArray(StandardCharsets.UTF_8)))
        } catch (exception: NoSuchAlgorithmException) {
            throw IllegalStateException("SHA-256 is not available", exception)
        }
    }
}
