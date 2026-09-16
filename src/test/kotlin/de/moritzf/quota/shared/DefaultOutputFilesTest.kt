package de.moritzf.quota.shared

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultOutputFilesTest {
    @Test
    fun resolveInsideBaseKeepsRelativePath() {
        val dir = Path.of("/tmp/project").toAbsolutePath().normalize()
        assertEquals(dir.resolve("out/hi.png"), DefaultOutputFiles.resolveInsideBase("out/hi.png", dir, null))
    }

    @Test
    fun resolveInsideBaseRejectsAbsoluteAndEscape() {
        val dir = Path.of("/tmp/project").toAbsolutePath().normalize()
        assertNull(DefaultOutputFiles.resolveInsideBase("/tmp/evil.png", dir, null))
        assertNull(DefaultOutputFiles.resolveInsideBase("../evil.png", dir, null))
    }

    @Test
    fun resolveInsideBaseUsesDefaultNameWhenTargetMissing() {
        val dir = Path.of("/tmp/project").toAbsolutePath().normalize()
        val resolved = DefaultOutputFiles.resolveInsideBase(null, dir, "speech-x.mp3")
        assertEquals(dir.resolve("speech-x.mp3"), resolved)
        assertNull(DefaultOutputFiles.resolveInsideBase(null, null, "speech-x.mp3"))
    }

    @Test
    fun speechFileNamesAreUnique() {
        val first = DefaultOutputFiles.speech("mp3")
        val second = DefaultOutputFiles.speech("mp3")
        assertTrue(first.matches(Regex("speech-[0-9a-f-]{36}\\.mp3")))
        assertNotEquals(first, second)
    }

    @Test
    fun imageFileNamesAreUnique() {
        val first = DefaultOutputFiles.image()
        val second = DefaultOutputFiles.image()
        assertTrue(first.matches(Regex("image-[0-9a-f-]{36}\\.png")))
        assertNotEquals(first, second)
    }
}
