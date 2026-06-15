package de.moritzf.proxy.config

import java.util.Locale

internal object HostBinding {
    fun isLocalOnlyHost(host: String?): Boolean {
        if (host.isNullOrBlank()) {
            return true
        }
        val normalized = host.trim().lowercase(Locale.ROOT)
        return normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            normalized.startsWith("127.")
    }

    fun clientHostForBindHost(host: String?): String {
        if (host.isNullOrBlank()) {
            return ServerConfig.DEFAULT_HOST
        }
        val normalized = host.trim().lowercase(Locale.ROOT)
        if (normalized == "0.0.0.0" || normalized == "::" || normalized == "0:0:0:0:0:0:0:0") {
            return ServerConfig.DEFAULT_HOST
        }
        return host.trim()
    }

    fun hostForUri(host: String): String {
        return if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
    }
}
