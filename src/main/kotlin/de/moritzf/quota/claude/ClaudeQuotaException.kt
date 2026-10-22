package de.moritzf.quota.claude

import java.time.Duration

class ClaudeQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
    /** How long the server asked to wait (`Retry-After`), when it said. */
    val retryAfter: Duration? = null,
) : RuntimeException(message, cause)
