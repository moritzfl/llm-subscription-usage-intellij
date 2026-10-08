package de.moritzf.quota.idea.kimi

import de.moritzf.quota.kimi.KimiDeviceHeaders
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KimiDeviceIdStoreTest {
    @Test
    fun preservesExistingInstallationIdentity() {
        val store =
            KimiDeviceIdStore(read = { "existing-device" }, write = { error("Must not overwrite") })
        assertEquals("existing-device", store.deviceId)
    }

    @Test
    fun concurrentFirstUsePersistsOnceAndNextStoreLoadsSameIdentity() {
        var persisted: String? = null
        val writes = AtomicInteger()
        val store =
            KimiDeviceIdStore(
                read = { persisted },
                write = {
                    persisted = it
                    writes.incrementAndGet()
                },
            )
        Executors.newFixedThreadPool(4).use { executor ->
            val results =
                (1..16)
                    .map { executor.submit<String> { store.deviceId } }
                    .map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, results.distinct().size)
            assertEquals(1, writes.get())
            assertTrue(results.first().isNotBlank())
            assertEquals(
                results.first(),
                KimiDeviceIdStore(read = { persisted }, write = { error("Must not overwrite") })
                    .deviceId,
            )
        }
    }

    @Test
    fun unavailablePersistenceKeepsStableProcessIdentity() {
        val failedRead =
            KimiDeviceIdStore(read = { error("unavailable") }, write = { error("unexpected") })
        val failedWrite = KimiDeviceIdStore(read = { null }, write = { error("unavailable") })
        assertEquals(KimiDeviceHeaders.processDeviceId, failedRead.deviceId)
        assertEquals(failedRead.deviceId, failedWrite.deviceId)
        assertEquals(KimiDeviceHeaders.all()["X-Msh-Device-Id"], failedRead.deviceId)
    }
}
