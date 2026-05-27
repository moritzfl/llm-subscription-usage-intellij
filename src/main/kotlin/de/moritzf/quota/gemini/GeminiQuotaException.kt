package de.moritzf.quota.gemini

import java.io.IOException

class GeminiQuotaException(
    message: String,
    val statusCode: Int = -1,
    val responseBody: String? = null,
    cause: Throwable? = null
) : IOException(message, cause)
