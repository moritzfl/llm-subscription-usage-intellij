package de.moritzf.quota.kimi

import com.intellij.ide.util.PropertiesComponent
import java.net.InetAddress
import java.util.UUID

object KimiDeviceHeaders {
    private const val KEY_DEVICE_ID = "kimi.oauth.device.id"
    private const val PLATFORM = "kimi_cli"
    private const val VERSION = "1.40.0"

    fun all(): Map<String, String> = mapOf(
        "X-Msh-Platform" to PLATFORM,
        "X-Msh-Version" to VERSION,
        "X-Msh-Device-Name" to deviceName(),
        "X-Msh-Device-Model" to deviceModel(),
        "X-Msh-Os-Version" to osVersion(),
        "X-Msh-Device-Id" to deviceId(),
    )

    private fun sanitize(value: String): String {
        return try {
            String(value.toByteArray(Charsets.US_ASCII)).trim()
        } catch (_: Exception) {
            value.trim().replace(Regex("[^\\x00-\\x7F]"), "")
        }.ifBlank { "unknown" }
    }

    private fun deviceName(): String {
        return sanitize(
            runCatching { InetAddress.getLocalHost().hostName }
                .getOrElse { "unknown" },
        )
    }

    private fun deviceModel(): String {
        val system = System.getProperty("os.name") ?: ""
        val arch = System.getProperty("os.arch") ?: ""
        val version = System.getProperty("os.version") ?: ""
        return when {
            system.startsWith("Mac") -> buildModel("macOS", macProductVersion() ?: version, arch)
            system.startsWith("Windows") -> buildModel("Windows", windowsRelease(version), arch)
            system.isNotBlank() -> buildModel(system, version, arch)
            else -> "Unknown"
        }
    }

    private fun osVersion(): String {
        return sanitize(System.getProperty("os.version") ?: "unknown")
    }

    private fun deviceId(): String {
        return try {
            val props = PropertiesComponent.getInstance()
            val existing = props.getValue(KEY_DEVICE_ID)
            if (!existing.isNullOrBlank()) return existing
            val newId = UUID.randomUUID().toString().replace("-", "")
            props.setValue(KEY_DEVICE_ID, newId)
            newId
        } catch (_: Exception) {
            UUID.randomUUID().toString().replace("-", "")
        }
    }

    private fun macProductVersion(): String? {
        return runCatching {
            val process = ProcessBuilder("sw_vers", "-productVersion")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            output.ifBlank { null }
        }.getOrNull()
    }

    private fun windowsRelease(kernelVersion: String): String {
        val parts = kernelVersion.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: return kernelVersion
        val build = parts.getOrNull(2)?.toIntOrNull()
        return when {
            major == 10 && build != null && build >= 22000 -> "11"
            else -> major.toString()
        }
    }

    private fun buildModel(os: String, version: String, arch: String): String {
        return listOfNotNull(
            os,
            version.takeUnless { it.isBlank() },
            arch.takeUnless { it.isBlank() },
        ).joinToString(" ")
    }
}
