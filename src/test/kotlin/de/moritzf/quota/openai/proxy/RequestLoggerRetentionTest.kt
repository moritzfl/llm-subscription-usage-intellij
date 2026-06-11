package de.moritzf.quota.openai.proxy

import com.aiproxyoauth.logging.RequestLogger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class RequestLoggerRetentionTest {

    @Test
    fun prunesLogsOlderThanRetentionWindow() {
        val dir = Files.createTempDirectory("request-logger-age")
        try {
            val stale = writeLog(dir, "stale", daysAgo = 30)
            val fresh = writeLog(dir, "fresh", daysAgo = 1)

            RequestLogger(true, dir).pruneOldLogs()

            assertTrue(Files.notExists(stale), "stale log should be pruned")
            assertTrue(Files.exists(fresh), "fresh log should be retained")
        } finally {
            deleteRecursively(dir)
        }
    }

    @Test
    fun trimsToNewestFilesWhenCountExceedsCap() {
        val dir = Files.createTempDirectory("request-logger-count")
        try {
            // MAX_LOG_FILES is 2000; create a few over the cap. Index 0 is the oldest and
            // index N-1 the newest, so the lowest indices must be the ones pruned.
            val total = 2003
            val files = (0 until total).map { index ->
                writeLog(dir, "entry-$index", daysAgo = 0, ageOffsetMillis = (total - index).toLong())
            }

            RequestLogger(true, dir).pruneOldLogs()

            val survivors = Files.list(dir).use { stream -> stream.count() }
            assertTrue(survivors <= 2000L, "directory should be trimmed to the cap, was $survivors")
            // The three oldest (indices 0..2) must be gone; the newest must remain.
            assertContentEquals(
                listOf(false, false, false),
                files.take(3).map { Files.exists(it) },
            )
            assertTrue(Files.exists(files.last()), "newest log should be retained")
        } finally {
            deleteRecursively(dir)
        }
    }

    private fun writeLog(dir: Path, name: String, daysAgo: Long, ageOffsetMillis: Long = 0): Path {
        val file = dir.resolve("$name.json")
        Files.writeString(file, "{}")
        val millis = System.currentTimeMillis() - daysAgo * 24L * 60 * 60 * 1000 - ageOffsetMillis
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis))
        return file
    }

    private fun deleteRecursively(dir: Path) {
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
