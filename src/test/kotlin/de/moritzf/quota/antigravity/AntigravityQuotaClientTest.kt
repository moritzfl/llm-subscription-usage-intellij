package de.moritzf.quota.antigravity

import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AntigravityQuotaClientTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun requiresVersionWithDocumentedUsageCommand() {
        for (version in listOf("1.1.11", "1.2.8", "2.0.0")) {
            assertTrue(AntigravityQuotaClient.supportsUsageReport(version), version)
        }
        for (version in listOf("1.0.3", "1.1.10", "0.9.99", "1.1", "1.1.11-beta", "unknown")) {
            assertFalse(AntigravityQuotaClient.supportsUsageReport(version), version)
        }
    }

    @Test
    fun missingExecutableGivesActionableError() {
        val error = assertFailsWith<AntigravityQuotaException> {
            AntigravityQuotaClient(executableProvider = { null }).fetchQuota()
        }
        assertTrue(error.message!!.contains("executable not found"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun invokesOnlyVersionAndStructuredUsageInDisposableWorkspace() {
        Files.writeString(directory.resolve("usage.json"), USAGE_REPORT)
        val binary = executable("""
            if read -r ignored; then exit 44; fi
            printf '%s\n' "${'$'}AGY_CLI_DISABLE_AUTO_UPDATE" > ${quote(directory.resolve("auto-update"))}
            printf 'diagnostics' > agy.log
            cat ${quote(directory.resolve("usage.json"))}
        """.trimIndent())

        val quota = AntigravityQuotaClient(executableProvider = { binary }).fetchQuota()

        assertEquals(USAGE_REPORT, quota.rawJson?.trim())
        val cwd = Path.of(Files.readString(directory.resolve("cwd")).trim())
        assertFalse(Files.exists(cwd), "Temporary workspace and CLI diagnostics must be deleted")
        val args = Files.readAllLines(directory.resolve("args"))
        assertEquals(
            listOf("--version", "-p", "/usage", "--output-format", "json", "--print-timeout", "90s", "--log-file"),
            args.dropLast(1),
        )
        assertEquals(cwd.resolve("agy.log").toFile().canonicalPath, Path.of(args.last()).toFile().canonicalPath)
        assertEquals("true", Files.readString(directory.resolve("auto-update")).trim())
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun oldCliNeverReceivesUsageAsPossibleModelPrompt() {
        val binary = executable("exit 99", version = "1.0.3")
        val error = assertFailsWith<AntigravityQuotaException> {
            AntigravityQuotaClient(executableProvider = { binary }).fetchQuota()
        }
        assertTrue(error.message!!.contains("1.1.11"))
        assertEquals(listOf("--version"), Files.readAllLines(directory.resolve("args")))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    @Timeout(10)
    fun timeoutTerminatesCommandAndCleansWorkspace() {
        val binary = executable("""
            printf '%s' "${'$'}${'$'}" > ${quote(directory.resolve("pid"))}
            exec sleep 30
        """.trimIndent())
        val error = assertFailsWith<AntigravityQuotaException> {
            AntigravityQuotaClient(executableProvider = { binary }, timeoutMillis = 500).fetchQuota()
        }

        assertTrue(error.message!!.contains("timed out"))
        val handle = ProcessHandle.of(Files.readString(directory.resolve("pid")).toLong()).orElse(null)
        handle?.onExit()?.get(3, TimeUnit.SECONDS)
        assertTrue(handle == null || !handle.isAlive)
        assertFalse(Files.exists(Path.of(Files.readString(directory.resolve("cwd")).trim())))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    @Timeout(10)
    fun capsOutputBeforeParsingIt() {
        val binary = executable("exec head -c 1048577 /dev/zero")
        val error = assertFailsWith<AntigravityQuotaException> {
            AntigravityQuotaClient(executableProvider = { binary }).fetchQuota()
        }
        assertTrue(error.message!!.contains("size limit"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun failedCliDoesNotExposeRawDiagnostics() {
        val binary = executable("printf 'private-diagnostic' >&2\nprintf 'private-stdout'\nexit 1")
        val error = assertFailsWith<AntigravityQuotaException> {
            AntigravityQuotaClient(executableProvider = { binary }).fetchQuota()
        }
        assertTrue(error.message!!.contains("sign-in"))
        assertFalse(error.message!!.contains("private"))
    }

    @Test
    @EnabledOnOs(OS.MAC, OS.LINUX)
    fun resolvesExplicitPathsAndIgnoresRelativePathEntries() {
        val home = Files.createDirectories(directory.resolve("home"))
        val fallback = Files.createDirectories(home.resolve(".local/bin")).resolve("agy")
        Files.writeString(fallback, "#!/bin/sh\nexit 0\n")
        assertTrue(fallback.toFile().setExecutable(true))
        val selected = executable("exit 0")

        assertEquals(fallback, AntigravityQuotaClient.findExecutable(environment = mapOf("PATH" to ".:relative"), home = home.toString()))
        assertEquals(selected, AntigravityQuotaClient.findExecutable(selected.toString(), environment = emptyMap(), home = home.toString()))
        assertNull(AntigravityQuotaClient.findExecutable(directory.resolve("missing").toString(), environment = emptyMap(), home = home.toString()))
        assertNotNull(AntigravityQuotaClient.findExecutable(fallback.toString()))
    }

    @Test
    fun detectsWindowsPathAndLocalAppDataInstall() {
        val custom = Files.createDirectories(directory.resolve("custom")).resolve("agy.exe")
        Files.writeString(custom, "fixture")
        assertTrue(custom.toFile().setExecutable(true))
        val localAppData = directory.resolve("AppData")
        val standard = Files.createDirectories(localAppData.resolve("agy/bin")).resolve("agy.exe")
        Files.writeString(standard, "fixture")
        assertTrue(standard.toFile().setExecutable(true))
        val environment = mapOf("Path" to ".;${custom.parent}", "LOCALAPPDATA" to localAppData.toString())

        assertEquals(custom, AntigravityQuotaClient.findExecutable(environment = environment, home = directory.toString(), windows = true))
        assertEquals(standard, AntigravityQuotaClient.findExecutable(environment = environment - "Path", home = directory.toString(), windows = true))
    }

    private fun executable(body: String, version: String = "1.2.8"): Path {
        // Spaces and shell metacharacters must stay literal in ProcessBuilder arguments.
        val binary = directory.resolve("agy executable;literal")
        Files.writeString(binary, """
            |#!/bin/sh
            |printf '%s\n' "${'$'}@" >> ${quote(directory.resolve("args"))}
            |printf '%s\n' "${'$'}PWD" > ${quote(directory.resolve("cwd"))}
            |if [ "${'$'}1" = --version ]; then printf '%s\n' '$version'; exit 0; fi
            |$body
        """.trimMargin() + "\n")
        assertTrue(binary.toFile().setExecutable(true))
        return binary
    }

    private fun quote(path: Path): String = "'${path.toString().replace("'", "'\\''")}'"
}
