package de.moritzf.proxy.logging
import de.moritzf.proxy.server.MutableJsonObject
import de.moritzf.proxy.server.ProxyCall
import de.moritzf.proxy.server.createObjectNode
import de.moritzf.proxy.util.Json
import com.intellij.concurrency.virtualThreads.IntelliJVirtualThreads
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.Comparator
import java.util.Locale
import java.util.UUID
@Suppress("UnstableApiUsage")
class RequestLogger(
    private val enabled: Boolean,
    private val logDir: Path,
) {
    init {
        if (enabled) {
            // Prune once at startup off the request path.
            IntelliJVirtualThreads.ofVirtual().start(::pruneOldLogs)
        }
    }
    fun isEnabled(): Boolean = enabled
    fun nextRequestId(): String = "req_" + UUID.randomUUID().toString().replace("-", "")
    fun logInbound(requestId: String, ctx: ProxyCall, body: String?) {
        if (!enabled) {
            return
        }
        val entry = baseEntry(requestId, "inbound")
        entry.put("method", ctx.method())
        entry.put("path", ctx.path())
        entry.put("status", ctx.responseStatus())
        entry.set("headers", redactStringHeaders(ctx.headers()))
        putBody(entry, body)
        write(entry, requestId, "inbound")
    }
    fun logUpstreamRequest(
        requestId: String,
        method: String,
        path: String,
        headers: Map<String, String>?,
        body: String?,
    ) {
        if (!enabled) {
            return
        }
        val entry = baseEntry(requestId, "upstream_request")
        entry.put("method", method)
        entry.put("path", path)
        entry.set("headers", redactStringHeaders(headers))
        putBody(entry, body)
        write(entry, requestId, "upstream_request")
    }
    fun logUpstreamResponse(
        requestId: String,
        status: Int,
        headers: Map<String, List<String>>?,
        bodyPreview: String?,
    ) {
        if (!enabled) {
            return
        }
        val entry = baseEntry(requestId, "upstream_response")
        entry.put("status", status)
        entry.set("headers", redactListHeaders(headers))
        putBody(entry, bodyPreview)
        write(entry, requestId, "upstream_response")
    }
    fun logClientResponse(requestId: String, status: Int, body: String?) {
        if (!enabled) {
            return
        }
        val entry = baseEntry(requestId, "client_response")
        entry.put("status", status)
        putBody(entry, body)
        write(entry, requestId, "client_response")
    }
    /**
     * Deletes log files older than [MAX_LOG_AGE], then trims the directory to
     * [MAX_LOG_FILES] newest entries. Best-effort: failures are ignored so a
     * crowded or unreadable log directory never blocks the proxy.
     */
    fun pruneOldLogs() {
        if (!Files.isDirectory(logDir)) {
            return
        }
        try {
            Files.list(logDir).use { entries ->
                val files = entries
                    .filter(Files::isRegularFile)
                    .filter { it.fileName.toString().endsWith(".json") }
                    .toList()
                    .toMutableList()
                val cutoffMillis = System.currentTimeMillis() - MAX_LOG_AGE.toMillis()
                files.removeIf { deleteIfOlderThan(it, cutoffMillis) }
                if (files.size > MAX_LOG_FILES) {
                    files.sortWith(Comparator.comparingLong(::lastModifiedMillis))
                    val excess = files.size - MAX_LOG_FILES
                    for (index in 0 until excess) {
                        deleteQuietly(files[index])
                    }
                }
            }
        } catch (_: IOException) {
        }
    }
    private fun write(entry: MutableJsonObject, requestId: String?, stage: String) {
        try {
            Files.createDirectories(logDir)
            val safeRequestId = safeFilePart(requestId ?: "unknown")
            val file = logDir.resolve(
                "$safeRequestId-$stage-${Instant.now().toEpochMilli()}-${UUID.randomUUID()}.json",
            )
            Files.writeString(
                file,
                Json.INSTANCE.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), entry.build()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
        } catch (exception: IOException) {
            System.err.println("Warning: failed to write request log: ${exception.message}")
        }
    }
    private data class BodyCapture(
        val body: String,
        val truncated: Boolean,
    )
    companion object {
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val REDACTED = "[REDACTED]"
        private const val MAX_LOG_FILES = 2_000
        private val MAX_LOG_AGE: Duration = Duration.ofDays(7)
        private fun baseEntry(requestId: String, stage: String): MutableJsonObject {
            val entry = createObjectNode()
            entry.put("request_id", requestId)
            entry.put("timestamp", Instant.now().toString())
            entry.put("stage", stage)
            return entry
        }
        private fun redactStringHeaders(headers: Map<String, String>?): MutableJsonObject {
            val node = createObjectNode()
            headers?.forEach { (name, value) ->
                node.put(name, if (isSensitiveHeader(name)) REDACTED else value)
            }
            return node
        }
        private fun redactListHeaders(headers: Map<String, List<String>>?): MutableJsonObject {
            val node = createObjectNode()
            headers?.forEach { (name, values) ->
                val array = de.moritzf.proxy.server.createArrayNode()
                values.forEach { value ->
                    array.add(if (isSensitiveHeader(name)) REDACTED else value)
                }
                node.set(name, array)
            }
            return node
        }
        private fun isSensitiveHeader(name: String?): Boolean {
            if (name == null) {
                return false
            }
            val normalized = name.lowercase(Locale.ROOT)
            return normalized == "authorization" ||
                normalized == "proxy-authorization" ||
                normalized == "x-api-key" ||
                normalized == "openai-api-key" ||
                normalized.contains("cookie") ||
                normalized.contains("token") ||
                normalized.contains("secret") ||
                normalized.contains("key")
        }
        private fun putBody(entry: MutableJsonObject, body: String?) {
            val capture = captureBody(body)
            entry.put("body", capture.body)
            entry.put("truncated", capture.truncated)
        }
        private fun captureBody(body: String?): BodyCapture {
            if (body == null) {
                return BodyCapture("", false)
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= MAX_BODY_BYTES) {
                return BodyCapture(body, false)
            }
            return BodyCapture(String(bytes, 0, MAX_BODY_BYTES, StandardCharsets.UTF_8), true)
        }
        private fun safeFilePart(value: String): String {
            return value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
        private fun deleteIfOlderThan(path: Path, cutoffMillis: Long): Boolean {
            if (lastModifiedMillis(path) < cutoffMillis) {
                deleteQuietly(path)
                return true
            }
            return false
        }
        private fun lastModifiedMillis(path: Path): Long {
            return try {
                Files.getLastModifiedTime(path).toMillis()
            } catch (_: IOException) {
                Long.MAX_VALUE
            }
        }
        private fun deleteQuietly(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
            }
        }
    }
}
