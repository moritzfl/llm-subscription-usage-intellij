package de.moritzf.proxy.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.ktor.util.Attributes
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/** Provider request/response context, usable over HTTP or directly in process. */
abstract class ProxyCall {
    open val call: ApplicationCall
        get() = error("This request has no HTTP connection.")

    abstract val attributes: Attributes
    var handled: Boolean = false

    abstract fun method(): String

    abstract fun path(): String

    abstract fun header(name: String): String?

    abstract fun headers(): Map<String, String>

    abstract fun responseHeader(name: String, value: String)

    abstract fun responseContentLength(): String?

    abstract fun responseStatus(): Int

    abstract fun setStatus(status: Int)

    abstract suspend fun respondText(text: String, contentType: ContentType, status: HttpStatusCode)

    abstract suspend fun respondOutputStream(
        contentType: ContentType,
        status: HttpStatusCode,
        producer: suspend OutputStream.() -> Unit,
    )

    fun <T : Any> setAttribute(key: AttributeKey<T>, value: T) {
        attributes.put(key, value)
    }

    fun <T : Any> getAttribute(key: AttributeKey<T>): T? = attributes.getOrNull(key)

    companion object {
        operator fun invoke(call: ApplicationCall): ProxyCall = HttpProxyCall(call)
    }
}

private class HttpProxyCall(override val call: ApplicationCall) : ProxyCall() {
    override val attributes: Attributes
        get() = call.attributes

    override fun method(): String = call.request.httpMethod.value

    override fun path(): String = call.request.path()

    override fun header(name: String): String? = call.request.header(name)

    override fun headers(): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        call.request.headers.forEach { name, values -> result[name] = values.joinToString(",") }
        return result
    }

    override fun responseHeader(name: String, value: String) {
        call.response.headers.append(name, value)
    }

    override fun responseContentLength(): String? = call.response.headers[HttpHeaders.ContentLength]

    override fun responseStatus(): Int = call.response.status()?.value ?: 0

    override fun setStatus(status: Int) {
        call.response.status(HttpStatusCode.fromValue(status))
    }

    override suspend fun respondText(
        text: String,
        contentType: ContentType,
        status: HttpStatusCode,
    ) {
        call.respondText(text, contentType, status)
    }

    override suspend fun respondOutputStream(
        contentType: ContentType,
        status: HttpStatusCode,
        producer: suspend OutputStream.() -> Unit,
    ) {
        call.respondOutputStream(contentType, status, producer = producer)
    }
}

/** Captures a direct provider response without a server, socket, or local API key. */
internal class BufferedProxyCall(private val requestPath: String) : ProxyCall() {
    override val attributes = Attributes()
    private val responseHeaders = java.util.TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
    private val output = ByteArrayOutputStream()
    private var statusCode = 0
    val body: String
        get() = output.toString(Charsets.UTF_8)

    val contentType: String?
        get() = responseHeaders[HttpHeaders.ContentType]

    override fun method() = "POST"

    override fun path() = requestPath

    override fun headers() =
        mapOf(
            HttpHeaders.ContentType to "application/json",
            HttpHeaders.Accept to "application/json",
        )

    override fun header(name: String) =
        headers().entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    override fun responseHeader(name: String, value: String) {
        responseHeaders[name] = value
    }

    override fun responseContentLength() = responseHeaders[HttpHeaders.ContentLength]

    override fun responseStatus() = statusCode

    override fun setStatus(status: Int) {
        statusCode = status
    }

    override suspend fun respondText(
        text: String,
        contentType: ContentType,
        status: HttpStatusCode,
    ) {
        respondOutputStream(contentType, status) { write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun respondOutputStream(
        contentType: ContentType,
        status: HttpStatusCode,
        producer: suspend OutputStream.() -> Unit,
    ) {
        statusCode = status.value
        responseHeader(HttpHeaders.ContentType, contentType.toString())
        producer(output)
    }
}
