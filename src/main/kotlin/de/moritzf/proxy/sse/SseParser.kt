package de.moritzf.proxy.sse
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.function.Consumer
object SseParser {
    fun parse(input: InputStream): List<ServerSentEvent> {
        val events = mutableListOf<ServerSentEvent>()
        iterateEvents(input, Consumer { events += it })
        return events
    }

    fun iterateEvents(input: InputStream, consumer: Consumer<ServerSentEvent>) {
        // Note: the caller owns the InputStream; closing the BufferedReader here closes it too,
        // which is intentional because this method fully consumes the stream.
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            var eventType: String? = null
            val dataLines = mutableListOf<String>()
            var line = reader.readLine()
            while (line != null) {
                if (line.isEmpty()) {
                    if (eventType != null || dataLines.isNotEmpty()) {
                        consumer.accept(ServerSentEvent(eventType, dataLines.takeIf { it.isNotEmpty() }?.joinToString("\n")))
                        eventType = null
                        dataLines.clear()
                    }
                } else if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim()
                } else if (line.startsWith("data:")) {
                    var value = line.substring(5)
                    if (value.isNotEmpty() && value[0] == ' ') {
                        value = value.substring(1)
                    }
                    dataLines += value
                }
                // SSE comments (":...") and unknown fields (id:, retry:) are ignored per spec.
                line = reader.readLine()
            }
            if (eventType != null || dataLines.isNotEmpty()) {
                consumer.accept(ServerSentEvent(eventType, dataLines.takeIf { it.isNotEmpty() }?.joinToString("\n")))
            }
        }
    }
}
