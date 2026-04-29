package de.moritzf.quota.zai

class ZaiQuotaException(
    message: String,
    val statusCode: Int = 0,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
