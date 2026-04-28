package de.moritzf.quota.ollama

/**
 * Exception raised when the Ollama Cloud usage endpoint returns an unexpected response.
 */
class OllamaQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)