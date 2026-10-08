package de.moritzf.quota.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Counts final outcomes, not failed intermediate attempts (for example SVG followed by PNG). */
@Serializable
internal data class DocumentImageExportReport(
    @SerialName("requested_format") val requestedFormat: DocumentImageFormat,
    val dpi: Int,
    val svg: Int = 0,
    val png: Int = 0,
    val provider: Int = 0,
    val failed: Int = 0,
    val diagnostics: List<String> = emptyList(),
) {
    val succeeded: Int
        get() = svg + png + provider

    val total: Int
        get() = succeeded + failed
}
