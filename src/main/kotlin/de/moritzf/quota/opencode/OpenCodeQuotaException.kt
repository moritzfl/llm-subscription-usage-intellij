package de.moritzf.quota.opencode

import java.io.IOException

/**
 * Signals an OpenCode quota API request or response error with the associated HTTP status code.
 */
class OpenCodeQuotaException(
    message: String,
    val statusCode: Int,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)
