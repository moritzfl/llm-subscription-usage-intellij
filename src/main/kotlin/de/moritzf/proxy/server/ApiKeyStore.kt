package de.moritzf.proxy.server
import de.moritzf.proxy.util.ApiKeyUtils
import com.intellij.concurrency.virtualThreads.IntelliJVirtualThreads
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchService
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicReference
/**
 * Thread-safe, hot-reloadable API key store.
 *
 * Inline keys (from --api-key) are immutable for the process lifetime.
 * File keys (from --api-keys-file) are reloaded on WatchService events or
 * lazily when a 401 is issued and the file timestamp has advanced.
 *
 * Both sources are merged into a single Snapshot via AtomicReference,
 * so ProxyServer always sees a consistent (keys, adminKey) pair.
 */
@Suppress("UnstableApiUsage")
class ApiKeyStore(
    inlineKeys: Map<String, String>,
    filePath: String?,
    private val cliAdminKey: String?,
) {
    private data class Snapshot(
        val keys: Map<String, String>,
        val adminKey: String?,
    )
    private val inlineKeys: Map<String, String> = inlineKeys.toMap()
    private val filePath: String? = filePath?.takeIf { it.isNotBlank() }
    private val snapshot = AtomicReference(buildSnapshot(this.inlineKeys, emptyMap()))
    @Volatile
    private var lastModified: FileTime = FileTime.fromMillis(0)
    @Volatile
    private var watchThread: Thread? = null
    /** Returns the key owner name, or null if not found. */
    fun lookup(key: String?): String? = key?.let { snapshot.get().keys[it] }
    /** Returns the current admin key, or null if none configured. */
    fun adminKey(): String? = snapshot.get().adminKey
    /** True when any key enforcement is active (keys or admin key present). */
    fun isEnforcing(): Boolean {
        val current = snapshot.get()
        return current.keys.isNotEmpty() || current.adminKey != null
    }
    /** Exposed for testing: the file timestamp at the time of last successful load. */
    @Suppress("unused")
    fun lastModified(): FileTime = lastModified
    /**
     * Re-reads the keys file and atomically swaps the live snapshot.
     * On error or empty result, logs a warning and keeps the existing snapshot.
     */
    fun reload() {
        val currentFilePath = filePath ?: return
        try {
            val fileKeys = HashMap<String, String>()
            Files.readAllLines(Path.of(currentFilePath)).forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    ApiKeyUtils.parseKeyEntry(trimmed, fileKeys)
                }
            }
            if (fileKeys.isEmpty()) {
                System.err.println("Warning: Reloaded API keys file is empty — keeping existing keys")
                return
            }
            val next = buildSnapshot(inlineKeys, fileKeys)
            snapshot.set(next)
            lastModified = Files.getLastModifiedTime(Path.of(currentFilePath))
            println("INFO: Reloaded ${next.keys.size} API key(s) from $currentFilePath")
        } catch (exception: IOException) {
            System.err.println("Warning: Failed to reload API keys from $currentFilePath: ${exception.message}")
        }
    }
    /**
     * Reloads only when the file's last-modified timestamp is newer than the last
     * successful load. Called on every 401 as a backstop for missed WatchService events.
     */
    fun reloadIfFileChanged() {
        val currentFilePath = filePath ?: return
        try {
            val current = Files.getLastModifiedTime(Path.of(currentFilePath))
            if (current > lastModified) {
                reload()
            }
        } catch (_: IOException) {
        }
    }
    /**
     * Starts a virtual thread that watches the keys file's parent directory for
     * ENTRY_MODIFY events. No-op if no file path is configured or the directory
     * does not exist.
     */
    fun startWatching() {
        val currentFilePath = filePath ?: return
        val path = Path.of(currentFilePath)
        val dir = path.parent
        if (dir == null || !Files.exists(dir)) {
            System.err.println("Warning: Cannot watch API keys file directory: $dir")
            return
        }
        watchThread = IntelliJVirtualThreads.ofVirtual().start {
            try {
                FileSystems.getDefault().newWatchService().use { watcher ->
                    watchKeysFile(watcher, dir, path)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (exception: IOException) {
                System.err.println("Warning: API keys file watcher failed: ${exception.message}")
            }
        }
    }
    /** Interrupts the WatchService thread. Safe to call if watching was never started. */
    fun stopWatching() {
        watchThread?.interrupt()
    }
    private fun watchKeysFile(watcher: WatchService, dir: Path, path: Path) {
        dir.register(watcher, StandardWatchEventKinds.ENTRY_MODIFY)
        while (!Thread.currentThread().isInterrupted) {
            val key = watcher.take()
            key.pollEvents().forEach { event ->
                val changed = event.context() as? Path ?: return@forEach
                if (path.fileName == changed) {
                    reload()
                }
            }
            key.reset()
        }
    }
    private fun buildSnapshot(inline: Map<String, String>, fileKeys: Map<String, String>): Snapshot {
        val merged = HashMap(inline)
        merged.putAll(fileKeys)
        var adminKey = cliAdminKey
        if (adminKey == null) {
            for ((key, value) in merged) {
                if ("admin".equals(value, ignoreCase = true)) {
                    adminKey = key
                    break
                }
            }
        }
        if (adminKey != null) {
            merged.remove(adminKey)
        }
        return Snapshot(merged.toMap(), adminKey)
    }
}
