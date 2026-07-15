package de.moritzf.proxy.model
import de.moritzf.proxy.server.createObjectNode
import de.moritzf.proxy.server.intPath
import de.moritzf.proxy.server.longPath
import de.moritzf.proxy.server.pathOrNull
import de.moritzf.proxy.server.stringPath
import de.moritzf.proxy.server.stringPathOrNull
import de.moritzf.proxy.util.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
class CodexInstructionsProvider(
    mode: Mode?,
    configuredInstructions: String?,
    cacheDir: Path?,
    ttl: Duration?,
    clock: Clock?,
    fetcher: InstructionFetcher?,
) {
    enum class Mode {
        CONFIGURED,
        LATEST_CODEX,
    }
    private val mode: Mode = mode ?: Mode.CONFIGURED
    private val configuredInstructions: String = configuredInstructions ?: ""
    private val cacheDir: Path = cacheDir ?: defaultCacheDir()
    private val ttl: Duration = ttl ?: DEFAULT_TTL
    private val clock: Clock = clock ?: Clock.systemUTC()
    private val fetcher: InstructionFetcher = fetcher ?: throw NullPointerException("fetcher")
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    private val lock = ReentrantLock()
    constructor(configuredInstructions: String?) : this(
        Mode.CONFIGURED,
        configuredInstructions,
        defaultCacheDir(),
        DEFAULT_TTL,
        Clock.systemUTC(),
        defaultHttpFetcher(),
    )
    constructor(
        mode: Mode?,
        configuredInstructions: String?,
        cacheDir: Path?,
        ttl: Duration?,
        httpClient: HttpClient,
    ) : this(mode, configuredInstructions, cacheDir, ttl, Clock.systemUTC(), httpFetcher(httpClient))
    fun instructionsForModel(model: String?): String {
        if (mode == Mode.CONFIGURED) {
            return configuredInstructions
        }
        val modelFamily = modelFamily(model)
        var now = clock.instant()
        var cached = loadCache(modelFamily)
        if (cached != null && cached.isFresh(now, ttl)) {
            return cached.instructions
        }
        lock.lock()
        try {
            cached = loadCache(modelFamily)
            now = clock.instant()
            if (cached != null && cached.isFresh(now, ttl)) {
                return cached.instructions
            }
            val response = fetchLatest(modelFamily, cached)
            if (response != null && response.statusCode() == 304 && cached != null) {
                val refreshed = cached.withFetchedAt(now)
                saveCache(refreshed)
                return refreshed.instructions
            }
            if (response != null && response.statusCode() in 200..<300) {
                val instructions = extractInstructions(response.body())
                val updated = CacheEntry(
                    modelFamily,
                    sourceUri(modelFamily).toString(),
                    etagHeaderValue(response.headers()),
                    now,
                    instructions,
                )
                saveCache(updated)
                return updated.instructions
            }
        } catch (_: Exception) {
            // Latest-Codex mode is optional; stale cache or configured instructions are safer than failing requests.
        } finally {
            lock.unlock()
        }
        val fallback = loadCache(modelFamily)
        return fallback?.instructions ?: configuredInstructions
    }
    private fun fetchLatest(modelFamily: String, cached: CacheEntry?): FetchResponse? {
        val headers = HashMap<String, String>()
        if (!cached?.etag.isNullOrBlank()) {
            headers["If-None-Match"] = cached.etag
        }
        return fetcher.fetch(FetchRequest(modelFamily, sourceUri(modelFamily), headers.toMap()))
    }
    private fun loadCache(modelFamily: String): CacheEntry? {
        val cached = memoryCache[modelFamily]
        if (cached != null) {
            return cached
        }
        val cacheFile = cacheFile(modelFamily)
        if (!Files.exists(cacheFile)) {
            return null
        }
        return try {
            val node = Files.newBufferedReader(cacheFile).use { reader -> Json.INSTANCE.parseToJsonElement(reader.readText()) }
            val loaded = CacheEntry(
                node.stringPath("modelFamily", modelFamily),
                node.stringPath("sourceUrl", sourceUri(modelFamily).toString()),
                textOrNull(node.pathOrNull("etag")),
                parseInstant(node),
                node.stringPath("instructions", ""),
            )
            memoryCache[modelFamily] = loaded
            loaded
        } catch (_: Exception) {
            null
        }
    }

    private fun saveCache(entry: CacheEntry) {
        val node = createObjectNode()
        node.put("modelFamily", entry.modelFamily)
        node.put("sourceUrl", entry.sourceUrl)
        if (entry.etag != null) {
            node.put("etag", entry.etag)
        }
        node.put("fetchedAt", entry.fetchedAt.toString())
        node.put("timestamp", entry.fetchedAt.toString())
        node.put("fetchedAtEpochMillis", entry.fetchedAt.toEpochMilli())
        node.put("instructions", entry.instructions)
        Files.createDirectories(cacheDir)
        val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }
        Files.newBufferedWriter(cacheFile(entry.modelFamily)).use { writer ->
            writer.write(prettyJson.encodeToString(JsonObject.serializer(), node.build()))
        }
        memoryCache[entry.modelFamily] = entry
    }
    private fun cacheFile(modelFamily: String): Path = cacheDir.resolve(safeFileName(modelFamily) + ".json")
    fun interface InstructionFetcher {
        fun fetch(request: FetchRequest): FetchResponse?
    }
    @Suppress("unused")
    class FetchRequest(
        private val modelFamily: String,
        private val uri: URI,
        headers: Map<String, String>,
    ) {
        private val headers: Map<String, String> = headers.toMap()
        fun modelFamily(): String = modelFamily
        fun uri(): URI = uri
        fun headers(): Map<String, String> = headers
    }
    class FetchResponse(
        private val statusCode: Int,
        private val body: String?,
        headers: Map<String, String>?,
    ) {
        private val headers: Map<String, String> = headers?.toMap() ?: emptyMap()
        fun statusCode(): Int = statusCode
        fun body(): String? = body
        fun headers(): Map<String, String> = headers
    }
    private data class CacheEntry(
        val modelFamily: String,
        val sourceUrl: String,
        val etag: String?,
        val fetchedAt: Instant,
        val instructions: String,
    ) {
        fun isFresh(now: Instant, ttl: Duration): Boolean = !fetchedAt.plus(ttl).isBefore(now)
        fun withFetchedAt(fetchedAt: Instant): CacheEntry {
            return CacheEntry(modelFamily, sourceUrl, etag, fetchedAt, instructions)
        }
    }
    companion object {
        private val DEFAULT_TTL: Duration = Duration.ofMinutes(15)
        private const val DEFAULT_SOURCE_BASE = "https://chatgpt.com/backend-api/codex/instructions/"
        private val REASONING_SUFFIXES = arrayOf(
            "-minimal", "-medium", "-xhigh", "-max", "-ultra", "-none", "-high", "-low",
        )
        fun modelFamily(model: String?): String {
            if (model.isNullOrBlank()) {
                return "default"
            }
            val normalized = model.trim()
            val lower = normalized.lowercase(Locale.ROOT)
            for (suffix in REASONING_SUFFIXES) {
                if (lower.endsWith(suffix) && normalized.length > suffix.length) {
                    return normalized.substring(0, normalized.length - suffix.length)
                }
            }
            return normalized
        }
        private fun safeFileName(modelFamily: String): String {
            return modelFamily.replace(Regex("[^A-Za-z0-9._-]"), "_")
        }
        private fun sourceUri(modelFamily: String): URI {
            return URI.create(DEFAULT_SOURCE_BASE + safeFileName(modelFamily))
        }
        private fun extractInstructions(body: String?): String {
            if (body == null) {
                return ""
            }
            try {
                val parsed = Json.INSTANCE.parseToJsonElement(body)
                val instructions = parsed.pathOrNull("instructions")
                if (instructions is JsonPrimitive && instructions.isString) {
                    return instructions.content
                }
            } catch (_: Exception) {
            }
            return body
        }
        private fun etagHeaderValue(headers: Map<String, String>?): String? {
            if (headers.isNullOrEmpty()) {
                return null
            }
            for ((key, value) in headers) {
                if (key.equals("ETag", ignoreCase = true)) {
                    return value
                }
            }
            return null
        }
        private fun textOrNull(node: JsonElement?): String? {
            return if (node is JsonPrimitive && node.isString) node.content else null
        }
        private fun parseInstant(node: JsonElement): Instant {
            var fetchedAt = node.stringPathOrNull("fetchedAt")
            if (fetchedAt.isNullOrBlank()) {
                fetchedAt = node.stringPathOrNull("timestamp")
            }
            if (!fetchedAt.isNullOrBlank()) {
                return Instant.parse(fetchedAt)
            }
            val epochMillis = node.longPath("fetchedAtEpochMillis", 0L)
            return if (epochMillis > 0) Instant.ofEpochMilli(epochMillis) else Instant.EPOCH
        }
        private fun defaultCacheDir(): Path = Path.of("cache", "codex-instructions")
        private fun defaultHttpFetcher(): InstructionFetcher = httpFetcher(HttpClient.newHttpClient())
        private fun httpFetcher(httpClient: HttpClient): InstructionFetcher {
            return InstructionFetcher { request ->
                val builder = HttpRequest.newBuilder(request.uri())
                    .header("Accept", "application/json")
                    .GET()
                request.headers().forEach(builder::header)
                val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                val headers = HashMap<String, String>()
                response.headers().map().forEach { (key, values) ->
                    if (values.isNotEmpty()) {
                        headers[key] = values.first()
                    }
                }
                FetchResponse(response.statusCode(), response.body(), headers)
            }
        }
    }
}