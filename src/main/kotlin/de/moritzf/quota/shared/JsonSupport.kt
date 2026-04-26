package de.moritzf.quota.shared

import kotlinx.serialization.json.Json

internal object JsonSupport {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
