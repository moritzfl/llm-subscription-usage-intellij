package de.moritzf.quota.supergrok

class SuperGrokQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
