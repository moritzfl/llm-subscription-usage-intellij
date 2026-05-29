package de.moritzf.quota.cursor

class CursorQuotaException(
    message: String,
    val statusCode: Int = 0,
    val responseBody: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
