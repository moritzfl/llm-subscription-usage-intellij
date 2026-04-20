package de.moritzf.quota

import kotlinx.serialization.json.Json

internal object JsonSupport {
    val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
}
