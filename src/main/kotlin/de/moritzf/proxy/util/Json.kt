package de.moritzf.proxy.util

import kotlinx.serialization.json.Json

/**
 * Shared kotlinx.serialization Json instance. All packages that need JSON
 * serialization/deserialization should use this rather than importing
 * the server-layer JsonHelper, which would create circular dependencies.
 */
object Json {
    val INSTANCE: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}