package de.moritzf.proxy.media

import java.net.InetAddress
import java.net.URI

internal class RemoteImageHop(
    val statusCode: Int,
    val uri: URI,
    val location: String?,
    val contentType: String?,
    val body: ByteArray,
)

internal object SafeRemoteImageFetcher {
    const val MAX_REDIRECTS = 5

    fun get(
        url: String,
        send: (URI) -> RemoteImageHop?,
        isSafe: (URI) -> Boolean = ::isSafeUri,
    ): RemoteImageHop? {
        var current = runCatching { URI.create(url) }.getOrNull() ?: return null
        repeat(MAX_REDIRECTS + 1) {
            if (!isSafe(current)) return null
            val hop = send(current) ?: return null
            if (hop.statusCode in 200..<300) {
                return hop.takeIf { isSafe(it.uri) }
            }
            if (hop.statusCode !in 300..399) return null
            val location = hop.location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            current = current.resolve(location)
        }
        return null
    }

    /**
     * Allows public HTTP(S) only; blocks loopback, private, link-local, metadata, and CGNAT hosts.
     */
    fun isSafeUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val normalized = host.lowercase()
        if (
            normalized == "localhost" ||
                normalized.endsWith(".localhost") ||
                normalized == "metadata.google.internal"
        ) {
            return false
        }
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        if (addresses.isEmpty()) return false
        return addresses.none { address ->
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                isCarrierGradeNat(address) ||
                isUniqueLocalAddress(address) ||
                isMetadataAddress(address)
        }
    }

    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 4 && bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xff) in 64..127
    }

    private fun isUniqueLocalAddress(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc
    }

    private fun isMetadataAddress(address: InetAddress): Boolean {
        val bytes = address.address
        return bytes.size == 4 &&
            bytes[0] == 169.toByte() &&
            bytes[1] == 254.toByte() &&
            bytes[2] == 169.toByte() &&
            bytes[3] == 254.toByte()
    }
}
