package de.moritzf.quota.shared

import java.net.http.HttpRequest
import java.nio.file.Path

internal object MultipartFilePublisher {
    fun of(
        boundary: String,
        fields: List<Pair<String, String>>,
        file: Path? = null,
        filename: String = file?.fileName?.toString().orEmpty(),
    ): HttpRequest.BodyPublisher {
        val preamble = buildString {
            for ((name, value) in fields) {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"").append(name).append("\"\r\n\r\n")
                append(value).append("\r\n")
            }
            if (file != null) {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                    .append(filename)
                    .append("\"\r\n")
                append("Content-Type: application/octet-stream\r\n\r\n")
            }
        }
            .toByteArray()
        val closing = "\r\n--$boundary--\r\n".toByteArray()
        return if (file != null) {
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(preamble),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofByteArray(closing),
            )
        } else {
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(preamble),
                HttpRequest.BodyPublishers.ofByteArray(closing),
            )
        }
    }
}
