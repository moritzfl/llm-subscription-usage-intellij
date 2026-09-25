package de.moritzf.quota.shared

/** Local figure export, not an OCR request's input resolution. */
enum class DocumentImageFormat { SVG, PNG, PROVIDER }

data class DocumentImageOptions(
    val format: DocumentImageFormat = DocumentImageFormat.SVG,
    val dpi: Int = 300,
    val paddingPoints: Double = 2.0,
) {
    init {
        require(dpi in 72..600) { "Image export DPI must be between 72 and 600." }
        require(paddingPoints.isFinite() && paddingPoints in 0.0..72.0) {
            "Image padding must be between 0 and 72 PDF points (72 points = 1 inch)."
        }
    }
}
