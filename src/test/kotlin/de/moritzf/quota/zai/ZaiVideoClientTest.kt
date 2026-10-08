package de.moritzf.quota.zai

import kotlin.test.Test
import kotlin.test.assertEquals

class ZaiVideoClientTest {
    @Test
    fun taskIdAndStatusReadProviderFields() {
        assertEquals(
            "task_1",
            ZaiVideoClient.taskId("""{"id":"task_1","task_status":"PROCESSING"}"""),
        )
        assertEquals("SUCCESS", ZaiVideoClient.taskStatus("""{"task_status":"SUCCESS"}"""))
    }
}
