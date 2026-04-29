package de.moritzf.quota.minimax

class MiniMaxQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
