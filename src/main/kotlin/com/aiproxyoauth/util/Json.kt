package com.aiproxyoauth.util

import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Shared Jackson ObjectMapper instance. All packages that need JSON
 * serialization/deserialization should use this rather than importing
 * the server-layer JsonHelper, which would create circular dependencies.
 */
object Json {
    @JvmField
    val MAPPER: ObjectMapper = ObjectMapper()
}
