package de.moritzf.quota.shared

import java.net.http.HttpRequest
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultipartFilePublisherTest {
    @Test
    fun concatenatesFieldsAndFileBytes() {
        val dir = Files.createTempDirectory("multipart-file")
        val file = dir.resolve("doc.pdf")
        val contents = byteArrayOf(1, 2, 3, 4, 5)
        Files.write(file, contents)
        val publisher = MultipartFilePublisher.of("bound", listOf("purpose" to "ocr"), file)
        val body = readPublisher(publisher)
        val text = body.decodeToString()
        assertTrue(text.contains("name=\"purpose\""))
        assertTrue(text.contains("ocr"))
        assertTrue(text.contains("filename=\"doc.pdf\""))
        assertEquals(contents.toList(), extractFileBytes(body, contents.size).toList())
        assertEquals(body.size.toLong(), publisher.contentLength())
    }

    private fun extractFileBytes(body: ByteArray, size: Int): ByteArray {
        val marker = "\r\n\r\n".toByteArray()
        val start = body.lastIndexOfSlice(marker) + marker.size
        return body.copyOfRange(start, start + size)
    }

    private fun ByteArray.lastIndexOfSlice(needle: ByteArray): Int {
        outer@ for (i in size - needle.size downTo 0) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun readPublisher(publisher: HttpRequest.BodyPublisher): ByteArray {
        val chunks = CopyOnWriteArrayList<ByteArray>()
        val done = CountDownLatch(1)
        var error: Throwable? = null
        publisher.subscribe(
            object : Flow.Subscriber<ByteBuffer> {
                override fun onSubscribe(subscription: Flow.Subscription) {
                    subscription.request(Long.MAX_VALUE)
                }

                override fun onNext(item: ByteBuffer) {
                    val bytes = ByteArray(item.remaining())
                    item.get(bytes)
                    chunks += bytes
                }

                override fun onError(throwable: Throwable) {
                    error = throwable
                    done.countDown()
                }

                override fun onComplete() {
                    done.countDown()
                }
            }
        )
        assertTrue(done.await(5, TimeUnit.SECONDS))
        error?.let { throw it }
        val size = chunks.sumOf { it.size }
        val out = ByteArray(size)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.size)
            offset += chunk.size
        }
        return out
    }
}
