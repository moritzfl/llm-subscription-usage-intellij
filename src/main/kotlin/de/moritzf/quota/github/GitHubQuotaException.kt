package de.moritzf.quota.github

class GitHubQuotaException(
    message: String,
    val statusCode: Int = 0,
    val rawBody: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
