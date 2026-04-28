package de.moritzf.quota.ollama

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

/**
 * HTTP client for fetching Ollama Cloud usage quota by scraping ollama.com/settings.
 */
open class OllamaQuotaClient(
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val endpoint: URI = DEFAULT_ENDPOINT,
) {
    @Throws(IOException::class, InterruptedException::class)
    open fun fetchQuota(sessionCookie: String, cfClearance: String?): OllamaQuota {
        require(sessionCookie.isNotBlank()) { "sessionCookie must not be null or blank" }

        val cookieHeader = buildCookieHeader(sessionCookie, cfClearance)

        val request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(Duration.ofSeconds(30))
            .header("Cookie", cookieHeader)
            .header("Accept", "text/html")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
            .GET()
            .build()

        LOG.info("Fetching Ollama quota from $endpoint")

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        val body = response.body()

        LOG.info("Ollama quota response: status=$status, bodyLen=${body.length}")

        if (status == 401 || status == 403) {
            throw OllamaQuotaException(
                "Ollama session cookie is invalid or expired (HTTP $status). " +
                    "Please update your session cookie in the plugin settings.",
                status, body
            )
        }
        if (status !in 200..299) {
            throw OllamaQuotaException(
                "Ollama quota request failed: HTTP $status. " +
                    "The ollama.com API may have changed — please report this issue if it persists.",
                status, body
            )
        }

        val quota = parseQuotaFromHtml(body)
        quota.fetchedAt = Clock.System.now()
        quota.rawJson = body
        return quota
    }

    private fun buildCookieHeader(sessionCookie: String, cfClearance: String?): String {
        val parts = mutableListOf("__Secure-session=$sessionCookie")
        if (!cfClearance.isNullOrBlank()) {
            parts.add("cf_clearance=$cfClearance")
        }
        return parts.joinToString("; ")
    }

    companion object {
        @JvmField
        val DEFAULT_ENDPOINT: URI = URI.create("https://ollama.com/settings")

        private val LOG = Logger.getLogger(OllamaQuotaClient::class.java.name)
        private val PLAN_VALUES = setOf("free", "pro", "max")

        /**
         * Parses Ollama quota data from the /settings HTML page using Jsoup.
         */
        fun parseQuotaFromHtml(html: String): OllamaQuota {
            val doc = Jsoup.parse(html)

            val plan = extractPlan(doc)
            val sessionUsage = extractUsageWindow(doc, "Session usage")
            val weeklyUsage = extractUsageWindow(doc, "Weekly usage")

            if (sessionUsage == null && weeklyUsage == null) {
                throw OllamaQuotaException(
                    "Could not parse Ollama quota from HTML. " +
                        "The ollama.com page format may have changed — please report this issue.",
                    200, html.take(500)
                )
            }

            return OllamaQuota(
                plan = plan,
                sessionUsage = sessionUsage,
                weeklyUsage = weeklyUsage,
            )
        }

        private fun extractPlan(doc: Document): String {
            // The plan badge is a <span class="... capitalize"> inside the "Cloud Usage" heading.
            // Try: find the h2 that contains "Cloud Usage", then look for a span with "capitalize" class.
            val h2 = doc.select("h2:contains(Cloud Usage)").first()
            if (h2 != null) {
                val badge = h2.select("span.capitalize").first()
                val text = badge?.text()?.trim()?.lowercase()
                if (text in PLAN_VALUES) return text!!
            }

            // Fallback: search any span with class containing "capitalize"
            for (span in doc.select("span[class~=capitalize]")) {
                val text = span.text().trim().lowercase()
                if (text in PLAN_VALUES) return text
            }

            return ""
        }

        private fun extractUsageWindow(doc: Document, label: String): OllamaUsageWindow? {
            val labelSpan = doc.select("span.text-sm").firstOrNull { it.text().trim() == label } ?: return null
            val windowBlock = findUsageWindowBlock(labelSpan) ?: return null
            val barDiv = windowBlock.select("div[style*=width:]").first() ?: return null
            val widthMatch = Regex("""width:\s*([0-9.]+)%""").find(barDiv.attr("style")) ?: return null
            val usagePercent = widthMatch.groupValues[1].toDoubleOrNull() ?: return null
            val resetsAt = windowBlock.select("[data-time]").first()?.attr("data-time")?.let { iso ->
                runCatching { Instant.parse(iso) }.getOrNull()
            }

            return OllamaUsageWindow(
                usagePercent = usagePercent,
                resetsAt = resetsAt,
            )
        }

        private fun findUsageWindowBlock(labelSpan: Element): Element? {
            var current: Element? = labelSpan.parent()
            while (current != null) {
                if (current.selectFirst("div[style*=width:]") != null && current.selectFirst("[data-time]") != null) {
                    return current
                }
                current = current.parent()
            }
            return null
        }
    }
}
