package de.moritzf.quota.shared.document

import java.nio.file.Files
import java.nio.file.Path
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument

internal object PdfPages {
    data class Range(val from: Int, val to: Int, val pageCount: Int) {
        val offset: Int
            get() = from - 1

        val isFullDocument: Boolean
            get() = from == 1 && to == pageCount
    }

    fun isPdf(path: Path): Boolean {
        if (!Files.isRegularFile(path)) return false
        Files.newInputStream(path).use { input ->
            val header = ByteArray(5)
            val read = input.read(header)
            return read == 5 && header.decodeToString() == "%PDF-"
        }
    }

    fun pageCount(path: Path): Int? {
        if (!isPdf(path)) return null
        return runCatching {
            Loader.loadPDF(path.toFile()).use { it.numberOfPages }
        }
            .getOrNull()
    }

    fun resolve(pageCount: Int, pageFrom: Int?, pageTo: Int?): Range? {
        if (pageCount < 1) return null
        val from = pageFrom ?: 1
        val to = pageTo ?: pageCount
        if (from < 1 || to < from || to > pageCount) return null
        return Range(from, to, pageCount)
    }

    fun writeSlice(source: Path, from: Int, to: Int, dest: Path): Boolean {
        return runCatching {
                Loader.loadPDF(source.toFile()).use { src ->
                    PDDocument().use { out ->
                        for (index in (from - 1) until to) {
                            out.importPage(src.getPage(index))
                        }
                        val parent = dest.parent
                        if (parent != null) Files.createDirectories(parent)
                        out.save(dest.toFile())
                    }
                }
                true
            }
            .getOrDefault(false)
    }
}
