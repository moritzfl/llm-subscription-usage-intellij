package de.moritzf.proxy.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes

class ProxyCall(val call: ApplicationCall) {
    val attributes: Attributes get() = call.attributes
    var handled: Boolean = false

    fun method(): String = call.request.httpMethod.value

    fun path(): String = call.request.path()

    fun header(name: String): String? = call.request.header(name)

    fun headers(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        call.request.headers.forEach { name, values ->
            result[name] = values.joinToString(",")
        }
        return result
    }

    fun responseHeader(name: String, value: String) {
        call.response.headers.append(name, value)
    }

    fun responseContentLength(): String? = call.response.headers[HttpHeaders.ContentLength]

    fun responseStatus(): Int = call.response.status()?.value ?: 0

    fun setStatus(status: Int) {
        call.response.status(HttpStatusCode.fromValue(status))
    }

    fun <T : Any> setAttribute(key: AttributeKey<T>, value: T) {
        attributes.put(key, value)
    }

    fun <T : Any> getAttribute(key: AttributeKey<T>): T? {
        return if (attributes.contains(key)) attributes[key] else null
    }
}
