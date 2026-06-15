package com.aiproxyoauth.auth

/**
 * Thrown when no upstream credentials are available (e.g. the user is not logged in).
 * Mapped to an HTTP 401 with an OpenAI-style error body instead of a generic 500.
 */
class AuthRequiredException(message: String) : RuntimeException(message)
