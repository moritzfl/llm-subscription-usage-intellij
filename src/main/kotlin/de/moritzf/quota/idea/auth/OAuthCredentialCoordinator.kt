package de.moritzf.quota.idea.auth

import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.Serializable
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.UUID

/**
 * Separate refresh and write locks let logout/new login win while a refresh is on the wire.
 * Production stores share a directory across IDEs. Only locks and hashed refresh receipts live
 * there; credentials remain exclusively in Password Safe. A receipt also covers the interval
 * between Password Safe accepting a write and its native-keychain queue making it visible.
 */
class OAuthCredentialCoordinator(private val directory: Path? = null) {
    private val refreshLock = lockFor("refresh")
    private val writeLock = lockFor("write")
    private var receipt: OAuthRefreshReceipt? = null
    private var writeVersion: String? = null

    fun <T> withRefreshLock(action: () -> T): T = withLock(refreshLock, "refresh", action)

    fun <T> withWriteLock(action: () -> T): T = withLock(writeLock, "write", action)

    // Call under the write lock. Versions also detect writes still queued by Password Safe.
    internal fun readWriteVersion(): String? {
        val path = directory?.resolve("write.version") ?: return writeVersion
        return if (Files.exists(path)) Files.readString(path) else null
    }

    internal fun recordWrite() {
        val version = UUID.randomUUID().toString()
        if (directory == null) writeVersion = version
        else writeAtomically(directory.resolve("write.version"), version)
    }

    // Call only while holding the refresh lock.
    internal fun readReceipt(): OAuthRefreshReceipt? {
        val path = directory?.resolve("refresh.json") ?: return receipt
        if (!Files.exists(path)) return null
        return JsonSupport.json.decodeFromString(Files.readString(path))
    }

    internal fun writeReceipt(value: OAuthRefreshReceipt) {
        val path = directory?.resolve("refresh.json")
        if (path == null) {
            receipt = value
            return
        }
        writeAtomically(path, JsonSupport.json.encodeToString(value))
    }

    private fun writeAtomically(path: Path, value: String) {
        val temporary = Files.createTempFile(path.parent, "oauth-", ".tmp")
        try {
            Files.writeString(temporary, value)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun lockFor(name: String): ReentrantLock = directory?.let {
        processLocks.computeIfAbsent(it.resolve("$name.lock").toAbsolutePath().normalize()) { ReentrantLock() }
    } ?: ReentrantLock()

    private fun <T> withLock(lock: ReentrantLock, name: String, action: () -> T): T {
        lock.lockInterruptibly()
        try {
            if (directory == null || lock.holdCount > 1) return action()
            Files.createDirectories(directory)
            return FileChannel.open(directory.resolve("$name.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use { action() }
            }
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()

        internal fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
internal data class OAuthRefreshReceipt(
    val credentialsHash: String,
    val outcome: Outcome,
    val attemptedAtMs: Long,
    val accessTokenRejected: Boolean = false,
) {
    enum class Outcome { TEMPORARY_FAILURE, REJECTED, ROTATED }

    fun matches(credentials: OAuthCredentials): Boolean = credentialsHash == fingerprint(credentials)

    companion object {
        fun fingerprint(credentials: OAuthCredentials): String =
            OAuthCredentialCoordinator.hash(JsonSupport.json.encodeToString(credentials))
    }
}
