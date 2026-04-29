package de.moritzf.quota.kimi

class KimiQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
