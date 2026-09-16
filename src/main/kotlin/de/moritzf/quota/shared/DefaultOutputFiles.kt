package de.moritzf.quota.shared

import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.UUID

internal object DefaultOutputFiles {
    fun speech(format: String): String = "speech-${UUID.randomUUID()}.$format"

    fun image(): String = "image-${UUID.randomUUID()}.png"

    fun resolveInsideBase(targetFile: String?, baseDirectory: Path?, defaultFileName: String?): Path? {
        val trimmed = targetFile?.trim()?.takeIf { it.isNotBlank() }
        if (trimmed == null) {
            if (defaultFileName == null || baseDirectory == null) return null
            return baseDirectory.resolve(defaultFileName).normalize()
        }
        val path = try {
            Path.of(trimmed)
        } catch (_: InvalidPathException) {
            return null
        }
        if (path.isAbsolute) return null
        val base = (baseDirectory ?: Path.of(System.getProperty("user.dir"))).toAbsolutePath().normalize()
        val resolved = base.resolve(path).normalize()
        if (!resolved.startsWith(base)) return null
        return resolved
    }
}
