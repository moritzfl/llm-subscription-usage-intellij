package de.moritzf.quota.antigravity

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Antigravity-only CLI boundary. Google owns login; this code only reads stdout. */
class AntigravityQuotaClient(
    private val executableProvider: () -> Path? = { findExecutable() },
    private val timeoutMillis: Long = 90_000,
    private val versionTimeoutMillis: Long = 5_000,
) {
    fun fetchQuota(): AntigravityQuota {
        val executable = executableProvider()
            ?: throw AntigravityQuotaException("AGY executable not found. Install Antigravity CLI or set its path in settings.")
        val directory = Files.createTempDirectory("llm-usage-agy-")
        try {
            // Older versions can interpret unknown slash commands as model prompts.
            val version = run(executable, listOf("--version"), directory, versionTimeoutMillis, 1_024).trim()
            if (!supportsUsageReport(version)) {
                throw AntigravityQuotaException("AGY 1.1.11 or later is required. Run agy update, then refresh.")
            }
            val raw = run(
                executable,
                listOf("-p", "/usage", "--output-format", "json", "--print-timeout", "90s", "--log-file", directory.resolve("agy.log").toString()),
                directory,
                timeoutMillis,
                MAX_OUTPUT_BYTES,
            )
            return parseAntigravityQuota(raw)
        } finally {
            // Do not retain the CLI's generated diagnostics or workspace files.
            runCatching {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    private fun run(executable: Path, args: List<String>, directory: Path, timeout: Long, maxBytes: Int): String {
        val process = try {
            ProcessBuilder(listOf(executable.toString()) + args)
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["AGY_CLI_DISABLE_AUTO_UPDATE"] = "true" }
                .start()
        } catch (_: IOException) {
            throw AntigravityQuotaException("Could not start AGY. Check the executable path in settings.")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout)
        val output = FutureTask { process.inputStream.readNBytes(maxBytes + 1) }
        try {
            process.outputStream.close() // Non-interactive EOF: never wait for login input.
            Thread(output, "agy-quota-output").apply { isDaemon = true }.start()
            val bytes = output.get(timeout, TimeUnit.MILLISECONDS)
            if (bytes.size > maxBytes) throw AntigravityQuotaException("AGY quota output exceeded the size limit.")
            if (!process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
                throw TimeoutException()
            }
            if (process.exitValue() != 0) {
                throw AntigravityQuotaException("AGY quota command failed. Run agy to check your sign-in, then refresh.")
            }
            return bytes.toString(Charsets.UTF_8)
        } catch (_: TimeoutException) {
            throw AntigravityQuotaException("AGY quota command timed out. Check AGY in a terminal, then retry.")
        } catch (_: ExecutionException) {
            throw AntigravityQuotaException("Could not read the AGY quota report.")
        } finally {
            if (process.isAlive) {
                process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
            output.cancel(true)
            runCatching { process.inputStream.close() }
        }
    }

    companion object {
        private const val MAX_OUTPUT_BYTES = 1_048_576

        internal fun supportsUsageReport(version: String): Boolean {
            if (!version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) return false
            val parts = version.split('.').map { it.toIntOrNull() ?: return false }
            return parts[0] > 1 || parts[0] == 1 && (parts[1] > 1 || parts[1] == 1 && parts[2] >= 11)
        }

        fun findExecutable(
            configuredPath: String? = null,
            environment: Map<String, String> = System.getenv(),
            home: String = System.getProperty("user.home"),
            windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        ): Path? {
            fun executable(value: String): Path? = runCatching {
                Path.of(value).takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
            }.getOrNull()
            if (!configuredPath.isNullOrBlank()) return executable(configuredPath.trim())
            val name = if (windows) "agy.exe" else "agy"
            val searchPath = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value.orEmpty()
            val candidates = buildList {
                searchPath.split(if (windows) ';' else File.pathSeparatorChar).filter { it.isNotBlank() }.forEach { dir ->
                    add("$dir/$name")
                }
                add("$home/.local/bin/$name")
                if (windows) {
                    environment["LOCALAPPDATA"]?.let { add("$it/agy/bin/$name") }
                    add("$home/AppData/Local/agy/bin/$name")
                } else {
                    add("/opt/homebrew/bin/agy")
                    add("/usr/local/bin/agy")
                }
            }
            return candidates.firstNotNullOfOrNull(::executable)
        }
    }
}
