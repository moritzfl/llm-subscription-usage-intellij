package de.moritzf.proxy.model

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodexInstructionsProviderTest {
    @Test
    fun latestCodexModeCreatesMissingCacheDirectory(@TempDir tempDir: Path) {
        val cacheDir = tempDir.resolve("missing-cache")
        val provider = CodexInstructionsProvider(
            CodexInstructionsProvider.Mode.LATEST_CODEX,
            "fallback instructions",
            cacheDir,
            Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
            CodexInstructionsProvider.InstructionFetcher {
                CodexInstructionsProvider.FetchResponse(200, "{\"instructions\":\"fresh instructions\"}", emptyMap())
            },
        )

        assertEquals("fresh instructions", provider.instructionsForModel("gpt-5.5"))
        assertTrue(Files.exists(cacheDir.resolve("gpt-5.5.json")))
    }
}
