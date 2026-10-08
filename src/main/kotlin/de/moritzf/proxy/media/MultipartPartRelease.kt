package de.moritzf.proxy.media

import io.ktor.http.content.PartData

// IntelliJ 2026.1's Ktor only has dispose; 2026.3 replaces it with suspend release.
// Resolve once against the IDE's version so newer IDEs use the nonblocking cleanup.
private val partCleanupGetter by lazy {
    PartData::class.java.methods.firstOrNull { it.name == "getRelease" && it.parameterCount == 0 }
        ?: PartData::class.java.getMethod("getDispose")
}

@Suppress("UNCHECKED_CAST")
internal suspend fun releaseMultipartPart(part: PartData) {
    val getter = partCleanupGetter
    val cleanup = getter.invoke(part)
    if (getter.name == "getRelease") {
        (cleanup as suspend () -> Unit)()
    } else {
        (cleanup as () -> Unit)()
    }
}
