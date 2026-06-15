package de.moritzf.proxy.server
import java.util.Locale
class UpstreamErrorMapper {
    data class MappedUpstreamError(
        val statusCode: Int,
        val body: String,
    )
    fun map(upstreamStatus: Int, upstreamBody: String?): MappedUpstreamError {
        val quotaExhausted = isQuotaExhausted(upstreamBody)
        val rateLimited = containsMarker(upstreamBody, "rate_limit_exceeded")
        // The Codex backend reports usage/rate limits with HTTP 404; clients expect 429.
        val status = if (upstreamStatus == 404 && (quotaExhausted || rateLimited)) 429 else upstreamStatus
        // "insufficient_quota" makes OpenAI-compatible clients stop and surface the quota problem.
        val errorType = if (quotaExhausted) "insufficient_quota" else null
        return MappedUpstreamError(status, JsonHelper.toUpstreamErrorBody(upstreamBody, status, errorType))
    }
    private fun isQuotaExhausted(upstreamBody: String?): Boolean {
        return containsMarker(upstreamBody, "usage_limit_reached") ||
            containsMarker(upstreamBody, "usage_not_included") ||
            containsMarker(upstreamBody, "usage limit")
    }
    private fun containsMarker(upstreamBody: String?, marker: String): Boolean {
        return upstreamBody != null && upstreamBody.lowercase(Locale.ROOT).contains(marker)
    }
}
