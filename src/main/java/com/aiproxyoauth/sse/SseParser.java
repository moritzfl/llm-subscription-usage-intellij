package com.aiproxyoauth.sse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SseParser {

    private SseParser() {}

    public static List<ServerSentEvent> parse(InputStream input) throws IOException {
        List<ServerSentEvent> events = new ArrayList<>();
        iterateEvents(input, events::add);
        return events;
    }

    public static void iterateEvents(InputStream input, Consumer<ServerSentEvent> consumer) throws IOException {
        // Note: the caller owns the InputStream; closing the BufferedReader here closes it too,
        // which is intentional — the stream is fully consumed by this method.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String eventType = null;
            List<String> dataLines = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // End of event block
                    if (eventType != null || !dataLines.isEmpty()) {
                        String data = dataLines.isEmpty() ? null : String.join("\n", dataLines);
                        consumer.accept(new ServerSentEvent(eventType, data));
                        eventType = null;
                        dataLines.clear();
                    }
                    continue;
                }

                if (line.startsWith("event:")) {
                    eventType = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    String value = line.substring(5);
                    if (!value.isEmpty() && value.charAt(0) == ' ') {
                        value = value.substring(1);
                    }
                    dataLines.add(value);
                }
                // SSE comments (":...") and unknown fields (id:, retry:) are ignored per spec.
            }

            // Handle trailing event without final blank line
            if (eventType != null || !dataLines.isEmpty()) {
                String data = dataLines.isEmpty() ? null : String.join("\n", dataLines);
                consumer.accept(new ServerSentEvent(eventType, data));
            }
        }
    }
}
