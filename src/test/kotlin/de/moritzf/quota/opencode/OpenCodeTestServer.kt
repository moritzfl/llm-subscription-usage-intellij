package de.moritzf.quota.opencode

import com.sun.net.httpserver.Headers
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

internal class OpenCodeTestServer(handler: (Request) -> Pair<Int, String>) : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Headers, val body: String)
    val requests = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val request = Request(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders,
                exchange.requestBody.bufferedReader().readText())
            requests += request
            val (status, body) = handler(request)
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }
    val endpoint: URI = URI.create("http://127.0.0.1:${server.address.port}/console/")
    override fun close() = server.stop(0)
}
