package de.moritzf.quota.shared

import de.moritzf.quota.openai.proxy.pdf.PdfFigureRenderer
import de.moritzf.quota.openai.proxy.pdf.PdfPages
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import org.apache.pdfbox.Loader

/** Open lazily, once per conversion, only when a figure actually needs the original PDF. */
internal object OriginalPdf {
    fun local(path: Path?): () -> PdfFigureRenderer? = {
        if (path != null && PdfPages.isPdf(path)) PdfFigureRenderer(Loader.loadPDF(path.toFile())) else null
    }

    fun bytes(bytes: ByteArray, mime: String): () -> PdfFigureRenderer? = {
        if (mime == "application/pdf") PdfFigureRenderer(Loader.loadPDF(bytes)) else null
    }

    fun source(localFile: Path?, documentUrl: String?): () -> PdfFigureRenderer? {
        if (!documentUrl.isNullOrBlank()) return { download(documentUrl) }
        return local(localFile)
    }

    private fun download(url: String): PdfFigureRenderer? {
        val uri = URI(url)
        val host = uri.host ?: throw IOException("Original PDF URL has no host")
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) ||
            host.equals("localhost", true) || host.endsWith(".localhost", true) || InetAddress.getAllByName(host).any {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress
            }
        ) throw IOException("Original PDF requires a public HTTPS URL")
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER)
            .build().use { client ->
            val response = client.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream()
            )
            response.body().use { input ->
                if (response.statusCode() != 200) throw IOException("Original PDF download returned HTTP ${response.statusCode()}")
                val bytes = input.readNBytes(MAX_BYTES + 1)
                if (bytes.size > MAX_BYTES) throw IOException("Original PDF exceeds the 50 MB figure-export download limit")
                if (bytes.size < 5 || bytes.copyOfRange(0, 5).decodeToString() != "%PDF-") return null
                return PdfFigureRenderer(Loader.loadPDF(bytes))
            }
        }
    }

    private const val MAX_BYTES = 50 * 1024 * 1024
}
