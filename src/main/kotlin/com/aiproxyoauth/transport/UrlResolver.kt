package com.aiproxyoauth.transport

import java.net.URI

object UrlResolver {
    private const val API_V1_PREFIX = "/v1"

    @JvmStatic
    fun resolveTargetUrl(input: String, baseUrl: String): String {
        val base = try {
            URI.create(baseUrl)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid base URL: $baseUrl", exception)
        }
        val basePath = stripTrailingSlash(base.path)
        val origin = base.scheme + "://" + base.authority

        var pathname: String
        var query = ""

        if (input.startsWith("http://") || input.startsWith("https://")) {
            val parsed = try {
                URI.create(input)
            } catch (exception: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid input URL: $input", exception)
            }
            pathname = parsed.path
            query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        } else {
            val queryIndex = input.indexOf('?')
            if (queryIndex >= 0) {
                pathname = input.substring(0, queryIndex)
                query = input.substring(queryIndex)
            } else {
                pathname = input
            }
        }

        if (pathname == basePath) {
            pathname = "/"
        } else if (basePath.isNotEmpty() && pathname.startsWith("$basePath/")) {
            pathname = pathname.substring(basePath.length)
        }

        if (pathname == API_V1_PREFIX) {
            pathname = "/"
        } else if (pathname.startsWith("$API_V1_PREFIX/")) {
            pathname = pathname.substring(API_V1_PREFIX.length)
        }

        return origin + basePath + pathname + query
    }

    private fun stripTrailingSlash(path: String?): String {
        return path?.removeSuffix("/").orEmpty()
    }
}
