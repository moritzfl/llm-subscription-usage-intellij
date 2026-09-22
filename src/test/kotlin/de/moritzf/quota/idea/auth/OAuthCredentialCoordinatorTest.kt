package de.moritzf.quota.idea.auth

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OAuthCredentialCoordinatorTest {
    @field:TempDir
    lateinit var directory: Path

    @Test
    fun refreshWaitsForAnotherJvmButCredentialWritesRemainAvailable() {
        val source = directory.resolve("LockHolder.java")
        Files.writeString(source, """
            import java.nio.channels.FileChannel;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;
            class LockHolder {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                         var lock = channel.lock()) {
                        System.out.println("locked");
                        System.out.flush();
                        System.in.read();
                    }
                }
            }
        """.trimIndent())
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            source.toString(), directory.resolve("refresh.lock").toString(),
        ).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        val coordinator = OAuthCredentialCoordinator(directory)
        val acquired = CountDownLatch(1)
        try {
            assertEquals("locked", process.inputStream.bufferedReader().readLine())
            val waiting = executor.submit {
                coordinator.withRefreshLock { acquired.countDown() }
            }
            assertFalse(acquired.await(200, TimeUnit.MILLISECONDS))
            assertEquals("write", coordinator.withWriteLock { "write" })
            process.outputStream.write(1)
            process.outputStream.flush()
            waiting.get(5, TimeUnit.SECONDS)
            assertTrue(acquired.await(1, TimeUnit.SECONDS))
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            // The OS lock must have been released, too.
            assertEquals("released", OAuthCredentialCoordinator(directory).withRefreshLock { "released" })
        } finally {
            process.destroyForcibly()
            executor.shutdownNow()
        }
    }
}
