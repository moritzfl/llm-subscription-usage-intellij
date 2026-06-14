package de.moritzf.quota.ollama

/**
 * Exception raised when an Ollama Cloud endpoint returns an unexpected response.
 */
class OllamaQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
