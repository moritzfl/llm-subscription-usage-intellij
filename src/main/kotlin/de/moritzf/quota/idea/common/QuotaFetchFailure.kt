package de.moritzf.quota.idea.common

private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SERVER_ERROR = 500

/**
 * A failed quota fetch that usually passes on its own: no connection or a timeout (status code 0,
 * used by the clients when the request never completed), a rate limit, or a server-side error.
 *
 * Everything else — expired logins, rejected keys, unreadable payloads — needs the user to act and
 * is therefore reported right away.
 */
internal fun isRateLimitFailure(statusCode: Int): Boolean = statusCode == HTTP_TOO_MANY_REQUESTS

internal fun isTransientFetchFailure(statusCode: Int): Boolean {
    return statusCode == 0 ||
        statusCode == HTTP_REQUEST_TIMEOUT ||
        statusCode == HTTP_TOO_MANY_REQUESTS ||
        statusCode >= HTTP_SERVER_ERROR
}
