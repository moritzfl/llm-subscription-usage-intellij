package com.aiproxyoauth.server

import java.io.IOException
import java.io.InputStream
import java.net.http.HttpResponse

/**
 * Honors LiteLLM's `x-litellm-num-retries` request header by retrying the upstream call
 * on failures where no model work could have happened: I/O errors and gateway statuses
 * 502/503/504. A bare 500 is intentionally not retried because it may be raised after
 * the request was metered/billed.
 */
object UpstreamRetry {
    private const val MAX_RETRIES = 3
    private const val RETRY_BACKOFF_MILLIS = 250L

    fun interface UpstreamCall {
        @Throws(Exception::class)
        fun send(): HttpResponse<InputStream>
    }

    @JvmStatic
    @Throws(Exception::class)
    fun withRetries(numRetriesHeader: String?, call: UpstreamCall): HttpResponse<InputStream> {
        val retries = parseRetries(numRetriesHeader)
        var attempt = 0
        while (true) {
            try {
                val response = call.send()
                if (attempt >= retries || !isRetryableStatus(response.statusCode())) {
                    return response
                }
                closeQuietly(response)
            } catch (exception: IOException) {
                if (attempt >= retries) {
                    throw exception
                }
            }
            attempt++
            Thread.sleep(RETRY_BACKOFF_MILLIS * attempt)
        }
    }

    private fun isRetryableStatus(status: Int): Boolean {
        return status == 502 || status == 503 || status == 504
    }

    private fun parseRetries(header: String?): Int {
        if (header.isNullOrBlank()) {
            return 0
        }
        return header.trim().toIntOrNull()?.coerceIn(0, MAX_RETRIES) ?: 0
    }

    private fun closeQuietly(response: HttpResponse<InputStream>) {
        try {
            response.body()?.use { it.readAllBytes() }
        } catch (_: Exception) {
        }
    }
}
