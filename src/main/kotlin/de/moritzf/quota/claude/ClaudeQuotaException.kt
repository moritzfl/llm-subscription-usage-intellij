package de.moritzf.quota.claude

class ClaudeQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
