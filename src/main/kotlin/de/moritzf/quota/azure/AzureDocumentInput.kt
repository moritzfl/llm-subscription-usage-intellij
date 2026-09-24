package de.moritzf.quota.azure

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64

internal class AzureDocumentSource(val bytes: ByteArray, val mime: String) {
    val dataUrl: String get() = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
}

internal class AzureDocumentInput(private val httpClient: HttpClient) {
    fun read(documentUrl: String?, localFile: Path?): AzureDocumentSource {
        val url = documentUrl?.trim().orEmpty()
        if (url.isNotEmpty() && localFile != null) throw AzureOcrException("Provide documentUrl or localFile, not both.")
        val bytes = if (url.isNotEmpty()) download(url) else {
            val path = localFile ?: throw AzureOcrException("Provide documentUrl or localFile.")
            if (!Files.isRegularFile(path)) throw AzureOcrException("Local document was not found.")
            if (Files.size(path) > MAX_DOCUMENT_BYTES) throw AzureOcrException("Azure document conversion supports files up to 20 MB.")
            Files.readAllBytes(path)
        }
        val mime = when {
            bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray()) -> "application/pdf"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            ) -> "image/png"

            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            else -> throw AzureOcrException("Azure document conversion supports PDF, PNG, or JPEG documents.")
        }
        return AzureDocumentSource(bytes, mime)
    }

    private fun download(value: String): ByteArray {
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw AzureOcrException("documentUrl must be a public HTTPS URL.")
        val host = uri.host ?: throw AzureOcrException("documentUrl must be a public HTTPS URL.")
        if (uri.scheme != "https" || uri.userInfo != null || uri.fragment != null || uri.port !in listOf(-1, 443) ||
            host.equals("localhost", ignoreCase = true) || host.endsWith(".localhost", ignoreCase = true) ||
            InetAddress.getAllByName(host).any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
            }
        ) throw AzureOcrException("documentUrl must be a public HTTPS URL.")
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90)).GET().build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { input ->
            if (response.statusCode() !in 200..299) throw AzureOcrException("Could not download document (HTTP ${response.statusCode()}).")
            val bytes = input.readNBytes(MAX_DOCUMENT_BYTES + 1)
            if (bytes.size > MAX_DOCUMENT_BYTES) throw AzureOcrException("Azure document conversion supports files up to 20 MB.")
            return bytes
        }
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024
    }
}
