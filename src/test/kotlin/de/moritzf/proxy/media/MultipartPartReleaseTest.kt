package de.moritzf.proxy.media

import io.ktor.http.Headers
import io.ktor.http.content.PartData
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class MultipartPartReleaseTest {
    @Test
    fun usesSuspendingReleaseWhenAvailableOtherwiseLegacyDispose() = runBlocking {
        val calls = mutableListOf<String>()
        val dispose = { calls.add("dispose"); Unit }
        val release: suspend () -> Unit = {
            delay(10)
            calls.add("release")
        }
        // Exercise the actual bundled Ktor API, including a genuinely suspending callback on newer IDEs.
        val newConstructor = PartData.FormItem::class.java.constructors.firstOrNull { it.parameterCount == 4 }
        val part = if (newConstructor != null) {
            newConstructor.newInstance("value", dispose, Headers.Empty, release) as PartData
        } else {
            PartData.FormItem("value", dispose, Headers.Empty)
        }
        releaseMultipartPart(part)
        assertEquals(listOf(if (newConstructor != null) "release" else "dispose"), calls)
    }
}
