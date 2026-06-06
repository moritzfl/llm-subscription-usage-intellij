package de.moritzf.quota.idea.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class McpJsonTargetUpdaterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun dotPathUpdatesNestedValueAndPreservesSiblings() {
        val updated = McpJsonTargetUpdater().updateContent(
            """
            {
              "mcpServers": {
                "jetbrains": {
                  "url": "http://localhost:1/sse",
                  "name": "IntelliJ"
                }
              },
              "enabled": true
            }
            """.trimIndent(),
            "mcpServers.jetbrains.url",
            "http://localhost:63342/sse",
        )

        val root = json.parseToJsonElement(updated).jsonObject
        val jetbrains = root["mcpServers"]!!.jsonObject["jetbrains"]!!.jsonObject
        assertEquals("http://localhost:63342/sse", jetbrains["url"]!!.jsonPrimitive.content)
        assertEquals("IntelliJ", jetbrains["name"]!!.jsonPrimitive.content)
        assertEquals(true, root["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun jsonPointerCreatesMissingObjects() {
        val updated = McpJsonTargetUpdater().updateContent(
            "{}",
            "/mcpServers/jetbrains/url",
            "http://localhost:63342/sse",
        )

        val root = json.parseToJsonElement(updated).jsonObject
        assertEquals(
            "http://localhost:63342/sse",
            root["mcpServers"]!!.jsonObject["jetbrains"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun escapedDotKeepsLiteralDotInPropertyName() {
        val updated = McpJsonTargetUpdater().updateContent(
            "{}",
            "mcpServers.jetbrains\\.url",
            "http://localhost:63342/sse",
        )

        val root = json.parseToJsonElement(updated).jsonObject
        assertEquals(
            "http://localhost:63342/sse",
            root["mcpServers"]!!.jsonObject["jetbrains.url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun transportBuildsExpectedUrls() {
        val endpoints = McpServerEndpoints("http://localhost:63342/sse", 63342)

        assertEquals("http://localhost:63342/sse", McpServerTransport.SSE.urlFor(endpoints))
        assertEquals("http://localhost:63342/mcp", McpServerTransport.STREAMABLE_HTTP.urlFor(endpoints))
    }
}
