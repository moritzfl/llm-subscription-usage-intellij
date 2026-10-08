package de.moritzf.quota.ollama

import com.sun.net.httpserver.HttpServer
import de.moritzf.quota.shared.JsonSupport
import java.net.InetSocketAddress
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OllamaVisionModelsTest {
    @Test
    fun discoversCapabilitiesInsteadOfTreatingEveryCloudModelAsVision() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            val body =
                """{"models":[{"name":"image-model"},{"name":"text-model"},{"name":"missing"},{"name":"broken"},{"name":"unknown"}]}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/api/show") { exchange ->
            val request =
                JsonSupport.json
                    .parseToJsonElement(exchange.requestBody.bufferedReader().use { it.readText() })
                    .jsonObject
            val model = request.getValue("model").jsonPrimitive.content
            val body =
                when (model) {
                    "image-model" -> """{"capabilities":["completion","vision","tools"]}"""
                    "text-model" -> """{"capabilities":["completion","tools"]}"""
                    "broken" -> "not json"
                    "unknown" -> "{}"
                    else -> """{"error":"unavailable"}"""
                }.toByteArray()
            exchange.sendResponseHeaders(if (model == "missing") 503 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val catalog =
                OllamaVisionModels(baseUri = URI.create("http://127.0.0.1:${server.address.port}/"))
            val models = catalog.discover("test-key")
            assertEquals(true, models["image-model"])
            assertEquals(false, models["text-model"])
            assertNull(models["missing"])
            assertNull(models["broken"])
            assertNull(models["unknown"])
            assertEquals(listOf("image-model"), OllamaVisionModels.choices(models, "text-model"))
            assertEquals(
                listOf("image-model", "missing"),
                OllamaVisionModels.choices(models, "missing"),
            )
            assertEquals(listOf("image-model"), OllamaVisionModels.choices(models, null))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun outagePreservesSavedChoiceWithoutAdvertisingUnverifiedModels() {
        assertEquals(listOf("saved-model"), OllamaVisionModels.choices(emptyMap(), "saved-model"))
        assertEquals(emptyList(), OllamaVisionModels.choices(mapOf("unknown" to null), null))
    }

    @Test
    fun failedModelListIsNotParsedAsAnEmptySuccessfulCatalog() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/tags") { exchange ->
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val catalog =
                OllamaVisionModels(baseUri = URI.create("http://127.0.0.1:${server.address.port}/"))
            assertFailsWith<IllegalStateException> { catalog.discover("invalid-key") }
        } finally {
            server.stop(0)
        }
    }
}
