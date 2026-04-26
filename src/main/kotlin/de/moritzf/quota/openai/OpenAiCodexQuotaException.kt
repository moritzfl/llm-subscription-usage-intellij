package de.moritzf.quota.openai

import java.io.IOException

/**
 * Signals a quota API request or response error with the associated HTTP status code.
 */
class OpenAiCodexQuotaException(
    message: String,
    val statusCode: Int,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
