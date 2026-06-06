package de.moritzf.quota.idea.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun jsonUpdatePreservesExistingFormattingOutsideTargetValue() {
        val updated = McpJsonTargetUpdater().updateContent(
            """
            {
                "model":"gpt-5.4",
                "mcpServers": { "jetbrains": { "url" : null, "name":"IntelliJ" } },
                "enabled":true
            }
            """.trimIndent(),
            "mcpServers.jetbrains.url",
            "http://localhost:63342/sse",
        )

        assertEquals(
            """
            {
                "model":"gpt-5.4",
                "mcpServers": { "jetbrains": { "url" : "http://localhost:63342/sse", "name":"IntelliJ" } },
                "enabled":true
            }
            """.trimIndent(),
            updated,
        )
    }

    @Test
    fun jsonPointerUpdatesExistingNullValue() {
        val updated = McpJsonTargetUpdater().updateContent(
            """
            {
              "mcpServers": {
                "jetbrains": {
                  "url": null
                }
              }
            }
            """.trimIndent(),
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
            """
            {
              "mcpServers": {
                "jetbrains.url": "http://localhost:1/sse"
              }
            }
            """.trimIndent(),
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
    fun missingPathIsRejected() {
        val error = assertFailsWith<IllegalStateException> {
            McpJsonTargetUpdater().updateContent(
                "{}",
                "mcpServers.jetbrains.url",
                "http://localhost:63342/sse",
            )
        }

        assertEquals("JSON property path does not exist: mcpServers.jetbrains.url", error.message)
    }

    @Test
    fun nonStringPathIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            McpJsonTargetUpdater().updateContent(
                """
                {
                  "mcpServers": {
                    "jetbrains": {
                      "url": true
                    }
                  }
                }
                """.trimIndent(),
                "mcpServers.jetbrains.url",
                "http://localhost:63342/sse",
            )
        }

        assertEquals("JSON property path must point to a string or null value.", error.message)
    }

    @Test
    fun targetValidationAcceptsStringAndNullOnly() {
        assertNull(
            McpJsonTargetUpdater.validateTargetContent(
                """
                {
                  "mcpServers": {
                    "jetbrains": {
                      "url": "http://localhost:1/sse",
                      "nextUrl": null
                    }
                  }
                }
                """.trimIndent(),
                "mcpServers.jetbrains.url",
            ),
        )
        assertNull(
            McpJsonTargetUpdater.validateTargetContent(
                """
                {
                  "mcpServers": {
                    "jetbrains": {
                      "url": "http://localhost:1/sse",
                      "nextUrl": null
                    }
                  }
                }
                """.trimIndent(),
                "mcpServers.jetbrains.nextUrl",
            ),
        )

        val missingPath = assertNotNull(
            McpJsonTargetUpdater.validateTargetContent("{}", "mcpServers.jetbrains.url"),
        )
        assertEquals(McpJsonTargetValidationProblem.PROPERTY, missingPath.problem)

        val nonStringPath = assertNotNull(
            McpJsonTargetUpdater.validateTargetContent(
                """
                {
                  "mcpServers": {
                    "jetbrains": {
                      "url": 63342
                    }
                  }
                }
                """.trimIndent(),
                "mcpServers.jetbrains.url",
            ),
        )
        assertEquals(McpJsonTargetValidationProblem.PROPERTY, nonStringPath.problem)
    }

    @Test
    fun formatDotPathEscapesSegments() {
        val formatted = McpJsonTargetUpdater.formatDotPath(listOf("mcpServers", "jetbrains.url", "slash\\key"))

        assertEquals("mcpServers.jetbrains\\.url.slash\\\\key", formatted)
        assertEquals(listOf("mcpServers", "jetbrains.url", "slash\\key"), McpJsonTargetUpdater.parsePropertyPath(formatted))
    }

    @Test
    fun likelyMcpServerPathPrefersUrlLeaf() {
        val likelyPath = McpJsonTargetUpdater.findLikelyMcpServerPath(
            listOf(
                "mcpServers.jetbrains",
                "mcpServers.jetbrains.command",
                "mcpServers.jetbrains.url",
                "servers.other.url",
            ),
        )

        assertEquals("mcpServers.jetbrains.url", likelyPath)
    }

    @Test
    fun likelyMcpServerPathRequiresMcpAndJetbrainsOrIntellij() {
        val likelyPath = McpJsonTargetUpdater.findLikelyMcpServerPath(
            listOf(
                "mcpServers.github.url",
                "servers.jetbrains.url",
                "tooling.intellijMcp.endpoint",
            ),
        )

        assertEquals("tooling.intellijMcp.endpoint", likelyPath)
    }

    @Test
    fun transportBuildsExpectedUrls() {
        val endpoints = McpServerEndpoints("http://localhost:63342/sse", 63342)

        assertEquals("http://localhost:63342/sse", McpServerTransport.SSE.urlFor(endpoints))
        assertEquals("http://localhost:63342/mcp", McpServerTransport.STREAMABLE_HTTP.urlFor(endpoints))
    }

    @Test
    fun tomlDotPathUpdatesNestedValueAndPreservesSiblings() {
        val updated = McpTomlTargetUpdater().updateContent(
            """
            model = "gpt-5.4"
            [mcp_servers.idea]
            url = "http://127.0.0.1:1/stream"

            [projects."/Users/moritz/Desktop/git/pebble"]
            trust_level = "trusted"
            """.trimIndent(),
            "mcp_servers.idea.url",
            "http://127.0.0.1:64342/stream",
        )

        assertEquals(
            """
            model = "gpt-5.4"
            [mcp_servers.idea]
            url = "http://127.0.0.1:64342/stream"

            [projects."/Users/moritz/Desktop/git/pebble"]
            trust_level = "trusted"
            """.trimIndent(),
            updated,
        )
    }

    @Test
    fun tomlUpdatePreservesExistingFormattingOutsideTargetValue() {
        val updated = McpTomlTargetUpdater().updateContent(
            """
            model = "gpt-5.4"
            [mcp_servers.idea]
            url    = "http://127.0.0.1:1/stream" # keep comment
            name = "IntelliJ"
            """.trimIndent(),
            "mcp_servers.idea.url",
            "http://127.0.0.1:64342/stream",
        )

        assertEquals(
            """
            model = "gpt-5.4"
            [mcp_servers.idea]
            url    = "http://127.0.0.1:64342/stream" # keep comment
            name = "IntelliJ"
            """.trimIndent(),
            updated,
        )
    }

    @Test
    fun tomlNonStringPathIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            McpTomlTargetUpdater().updateContent(
                """
                [mcp_servers.idea]
                url = true
                """.trimIndent(),
                "mcp_servers.idea.url",
                "http://127.0.0.1:64342/stream",
            )
        }

        assertEquals("TOML property path must point to a string value.", error.message)
    }

    @Test
    fun tomlStringPropertyPathsAreCollectedForChooser() {
        val paths = McpTomlTargetUpdater.collectStringPropertyPaths(
            """
            model = "gpt-5.4"
            [mcp_servers.idea]
            url = "http://127.0.0.1:1/stream"
            enabled = true
            """.trimIndent(),
        )

        assertEquals(listOf("mcp_servers.idea.url", "model"), paths.sorted())
    }

    @Test
    fun yamlDotPathUpdatesNestedValueAndPreservesSiblings() {
        val updated = McpYamlTargetUpdater().updateContent(
            """
            model: gpt-5.4
            mcp_servers:
              idea:
                url: http://127.0.0.1:1/stream
            projects:
              /Users/moritz/Desktop/git/pebble:
                trust_level: trusted
            """.trimIndent(),
            "mcp_servers.idea.url",
            "http://127.0.0.1:64342/stream",
        )

        assertEquals(
            """
            model: gpt-5.4
            mcp_servers:
              idea:
                url: "http://127.0.0.1:64342/stream"
            projects:
              /Users/moritz/Desktop/git/pebble:
                trust_level: trusted
            """.trimIndent(),
            updated,
        )
    }

    @Test
    fun yamlUpdatePreservesExistingFormattingOutsideTargetValue() {
        val updated = McpYamlTargetUpdater().updateContent(
            """
            model: gpt-5.4
            mcp_servers:
              idea:
                url: http://127.0.0.1:1/stream # keep comment
                name: IntelliJ
            """.trimIndent(),
            "mcp_servers.idea.url",
            "http://127.0.0.1:64342/stream",
        )

        assertEquals(
            """
            model: gpt-5.4
            mcp_servers:
              idea:
                url: "http://127.0.0.1:64342/stream" # keep comment
                name: IntelliJ
            """.trimIndent(),
            updated,
        )
    }

    @Test
    fun yamlNonStringPathIsRejected() {
        val error = assertFailsWith<IllegalArgumentException> {
            McpYamlTargetUpdater().updateContent(
                """
                mcp_servers:
                  idea:
                    url: true
                """.trimIndent(),
                "mcp_servers.idea.url",
                "http://127.0.0.1:64342/stream",
            )
        }

        assertEquals("YAML property path must point to a string value.", error.message)
    }

    @Test
    fun yamlStringPropertyPathsAreCollectedForChooser() {
        val paths = McpYamlTargetUpdater.collectStringPropertyPaths(
            """
            model: gpt-5.4
            mcp_servers:
              idea:
                url: http://127.0.0.1:1/stream
                enabled: true
            """.trimIndent(),
        )

        assertEquals(listOf("mcp_servers.idea.url", "model"), paths.sorted())
    }
}
