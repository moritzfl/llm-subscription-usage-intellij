package de.moritzf.quota.azure

import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.shared.lenientDoubleOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class AzureCliException(message: String, val transient: Boolean = false) : Exception(message)

internal data class AzureAccessToken(val accessToken: String, val expiresAtMillis: Long)

internal data class AzureCliAccount(
    val subscriptionId: String,
    val subscriptionName: String,
    val tenantId: String?,
    val userName: String?,
    val userType: String?,
    val isDefault: Boolean,
)

/**
 * Azure-only CLI boundary. Microsoft owns the login. This runs documented `az account` commands
 * and reads stdout. It does not read `~/.azure` or change the CLI's active subscription.
 */
internal class AzureCli(
    private val executable: Path,
    private val run: (Path, List<String>, Long, Int) -> String = ::runAzure,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val tokens = mutableMapOf<String, AzureAccessToken>()

    fun listAccounts(): List<AzureCliAccount> = parseAzureAccountList(runJson(listOf("account", "list", "--output", "json")))

    fun showAccount(subscriptionId: String?): AzureCliAccount {
        val args = buildList {
            add("account")
            add("show")
            if (!subscriptionId.isNullOrBlank()) {
                add("--subscription")
                add(subscriptionId)
            }
            add("--output")
            add("json")
        }
        return parseAzureAccount(runJson(args))
            ?: throw AzureCliException("Azure CLI did not return a subscription. Run az login, then retry.")
    }

    fun invalidate() = synchronized(lock) { tokens.clear() }

    fun accessToken(scope: String, subscriptionId: String?): AzureAccessToken {
        val key = "$scope|${subscriptionId.orEmpty()}"
        synchronized(lock) {
            tokens[key]?.takeIf { it.expiresAtMillis - clock() > REFRESH_BUFFER_MILLIS }?.let { return it }
        }
        val token = requestToken(scope, subscriptionId)
        synchronized(lock) { tokens[key] = token }
        return token
    }

    private fun requestToken(scope: String, subscriptionId: String?): AzureAccessToken {
        val scopeArgs = tokenArgs("--scope", scope, subscriptionId)
        val scoped = runCatching { runJson(scopeArgs) }
        val raw = scoped.getOrElse {
            val audience = scope.removeSuffix("/.default")
            if (audience == scope) throw it
            runJson(tokenArgs("--resource", audience, subscriptionId))
        }
        return parseAzureCliToken(raw, clock())
    }

    private fun tokenArgs(flag: String, value: String, subscriptionId: String?): List<String> = buildList {
        add("account")
        add("get-access-token")
        if (!subscriptionId.isNullOrBlank()) {
            add("--subscription")
            add(subscriptionId)
        }
        add(flag)
        add(value)
        add("--output")
        add("json")
    }

    private fun runJson(args: List<String>): String {
        return try {
            run(executable, args, COMMAND_TIMEOUT_MILLIS, MAX_OUTPUT_BYTES)
        } catch (exception: AzureCliException) {
            throw exception
        } catch (_: Exception) {
            throw AzureCliException("Could not run Azure CLI. Check the executable path, then retry.", transient = true)
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_MILLIS = 20_000L
        private const val MAX_OUTPUT_BYTES = 1_048_576
        private const val REFRESH_BUFFER_MILLIS = 60_000L

        fun findExecutable(
            configuredPath: String? = null,
            environment: Map<String, String> = System.getenv(),
            home: String = System.getProperty("user.home"),
            windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
        ): Path? {
            fun executable(value: String): Path? = runCatching {
                val path = Path.of(value)
                if (!path.isAbsolute || !Files.isRegularFile(path) || !Files.isExecutable(path)) return@runCatching null
                // Credentials live under ~/.azure. Never treat that tree as an install location.
                if (path.normalize().toString().replace('\\', '/').contains("/.azure/")) return@runCatching null
                path
            }.getOrNull()
            if (!configuredPath.isNullOrBlank()) return executable(configuredPath.trim())
            val name = if (windows) "az.cmd" else "az"
            val searchPath = environment.entries.firstOrNull { it.key.equals("PATH", ignoreCase = windows) }?.value.orEmpty()
            val candidates = buildList {
                searchPath.split(if (windows) ';' else File.pathSeparatorChar).filter { it.isNotBlank() }.forEach { dir ->
                    add("$dir/$name")
                    if (windows) add("$dir/az.exe")
                }
                add("$home/.local/bin/$name")
                if (windows) {
                    val programFiles = environment["ProgramFiles"] ?: "C:/Program Files"
                    val programFilesX86 = environment["ProgramFiles(x86)"] ?: "C:/Program Files (x86)"
                    add("$programFiles/Microsoft SDKs/Azure/CLI2/wbin/$name")
                    add("$programFilesX86/Microsoft SDKs/Azure/CLI2/wbin/$name")
                } else {
                    add("/opt/homebrew/bin/az")
                    add("/usr/local/bin/az")
                }
            }
            return candidates.firstNotNullOfOrNull(::executable)
        }
    }
}

internal fun runAzure(executable: Path, args: List<String>, timeoutMillis: Long, maxBytes: Int): String {
    val process = try {
        ProcessBuilder(listOf(executable.toString()) + args)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    } catch (_: IOException) {
        throw AzureCliException("Could not start Azure CLI. Check the executable path in settings.")
    }
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    val output = FutureTask { process.inputStream.readNBytes(maxBytes + 1) }
    try {
        process.outputStream.close()
        Thread(output, "azure-cli-output").apply { isDaemon = true }.start()
        val bytes = output.get(timeoutMillis, TimeUnit.MILLISECONDS)
        if (bytes.size > maxBytes) throw AzureCliException("Azure CLI output exceeded the size limit.", transient = true)
        if (!process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) {
            throw TimeoutException()
        }
        if (process.exitValue() != 0) {
            throw AzureCliException("Azure CLI command failed. Run az login, then retry.")
        }
        return bytes.toString(Charsets.UTF_8)
    } catch (exception: AzureCliException) {
        throw exception
    } catch (_: TimeoutException) {
        throw AzureCliException("Azure CLI command timed out. Check az login, then retry.", transient = true)
    } catch (_: ExecutionException) {
        throw AzureCliException("Could not read Azure CLI output.", transient = true)
    } finally {
        if (process.isAlive) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
        }
        output.cancel(true)
        runCatching { process.inputStream.close() }
    }
}

internal fun parseAzureCliToken(raw: String, nowMillis: Long): AzureAccessToken {
    val json = azureJsonObject(raw)
        ?: throw AzureCliException("Azure CLI did not return token JSON. Run az login, then retry.")
    val token = json.text("accessToken")
        ?: throw AzureCliException("Azure CLI did not return an access token. Run az login, then retry.")
    val expires = tokenExpiryMillis(json, nowMillis)
        ?: throw AzureCliException("Azure CLI returned an invalid token expiration.")
    return AzureAccessToken(token, expires)
}

internal fun parseAzureAccountList(raw: String): List<AzureCliAccount> {
    val element = runCatching { JsonSupport.json.parseToJsonElement(extractJson(raw)) }.getOrNull() ?: return emptyList()
    val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { item -> parseAzureAccount(item as? JsonObject) }
        .filter { it.subscriptionId.isNotEmpty() }
        .sortedWith(compareByDescending<AzureCliAccount> { it.userType.equals("user", ignoreCase = true) }.thenBy { it.subscriptionName })
}

internal fun parseAzureAccount(raw: String): AzureCliAccount? = parseAzureAccount(azureJsonObject(raw))

private fun parseAzureAccount(json: JsonObject?): AzureCliAccount? {
    val id = json?.text("id") ?: return null
    val user = json["user"] as? JsonObject
    return AzureCliAccount(
        subscriptionId = id,
        subscriptionName = json.text("name") ?: id,
        tenantId = json.text("tenantId"),
        userName = user?.text("name"),
        userType = user?.text("type"),
        isDefault = (json["isDefault"] as? JsonPrimitive)?.contentOrNull.equals("true", ignoreCase = true),
    )
}

private fun tokenExpiryMillis(json: JsonObject, nowMillis: Long): Long? {
    val expiresOn = json["expires_on"]?.lenientDoubleOrNull()
    if (expiresOn != null && expiresOn > 0) {
        val millis = if (expiresOn > 1_000_000_000_000.0) expiresOn else expiresOn * 1000.0
        return millis.toLong().takeIf { it > nowMillis - 86_400_000L }
    }
    val text = json.text("expiresOn") ?: return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it.toEpochMilliseconds() }
    for (pattern in listOf("yyyy-MM-dd HH:mm:ss.SSSSSS", "yyyy-MM-dd HH:mm:ss.SSS", "yyyy-MM-dd HH:mm:ss")) {
        val parsed = runCatching {
            LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return null
}

private fun azureJsonObject(raw: String): JsonObject? =
    runCatching { JsonSupport.json.parseToJsonElement(extractJson(raw)) as? JsonObject }.getOrNull()

private fun extractJson(raw: String): String {
    val trimmed = raw.trim().removePrefix("\uFEFF")
    if (trimmed.startsWith('{') || trimmed.startsWith('[')) return trimmed
    val start = trimmed.indexOfAny(charArrayOf('{', '['))
    val end = trimmed.lastIndexOfAny(charArrayOf('}', ']'))
    if (start < 0 || end <= start) return trimmed
    return trimmed.substring(start, end + 1)
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
