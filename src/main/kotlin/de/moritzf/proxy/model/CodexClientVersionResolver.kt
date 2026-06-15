package de.moritzf.proxy.model

import de.moritzf.proxy.util.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object CodexClientVersionResolver {
    const val FALLBACK_CODEX_CLIENT_VERSION: String = "0.121.0"

    private val VERSION_PATTERN = Regex("\\b\\d+\\.\\d+\\.\\d+\\b")
    private const val REGISTRY_URL = "https://registry.npmjs.org/@openai/codex/latest"
    private val cache = ConcurrentHashMap<String, String>()

    @JvmStatic
    fun resolve(configuredVersion: String?): String {
        val trimmedVersion = configuredVersion?.trim()
        if (!trimmedVersion.isNullOrEmpty()) {
            return trimmedVersion
        }
        return cache.computeIfAbsent("default") {
            resolveLocalCodexVersion()
                ?: resolveRemoteCodexVersion()
                ?: run {
                    System.err.println(
                        "Could not determine the Codex API version automatically. " +
                            "Falling back to $FALLBACK_CODEX_CLIENT_VERSION. " +
                            "Pass a version explicitly with --codex-version if you need to override it.",
                    )
                    FALLBACK_CODEX_CLIENT_VERSION
                }
        }
    }

    @JvmStatic
    fun resolveLocalCodexVersion(): String? {
        for (command in localCodexVersionCommands(isWindows())) {
            val version = normalizeVersion(runVersionCommand(command))
            if (version != null) {
                return version
            }
        }
        return null
    }

    @JvmStatic
    fun localCodexVersionCommands(windows: Boolean): List<List<String>> {
        return if (windows) {
            listOf(
                listOf("cmd.exe", "/c", "codex.cmd", "--version"),
                listOf("codex.exe", "--version"),
                listOf("codex", "--version"),
            )
        } else {
            listOf(listOf("codex", "--version"))
        }
    }

    private fun isWindows(): Boolean {
        return System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
    }

    @JvmStatic
    fun runVersionCommand(command: List<String>): String? {
        var process: Process? = null
        return try {
            process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                buildString {
                    var line = reader.readLine()
                    while (line != null) {
                        if (isNotEmpty()) {
                            append('\n')
                        }
                        append(line)
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {
            null
        } finally {
            process?.destroy()
        }
    }

    @JvmStatic
    fun resolveRemoteCodexVersion(): String? {
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(REGISTRY_URL))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                return null
            }
            val version = Json.MAPPER.readTree(response.body()).get("version")
            if (version != null && version.isTextual) normalizeVersion(version.asText()) else null
        } catch (_: Exception) {
            null
        }
    }

    @JvmStatic
    fun normalizeVersion(raw: String?): String? {
        return raw?.let { VERSION_PATTERN.find(it)?.value }
    }
}
