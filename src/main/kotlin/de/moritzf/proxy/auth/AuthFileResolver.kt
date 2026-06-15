package de.moritzf.proxy.auth

import java.nio.file.Path
import java.util.LinkedHashSet

object AuthFileResolver {
    private const val AUTH_FILENAME = "auth.json"

    @JvmStatic
    fun resolveCandidates(authFilePath: String?): List<String> {
        if (!authFilePath.isNullOrEmpty()) {
            return listOf(authFilePath)
        }

        val candidates = LinkedHashSet<String>()

        val envHome = System.getenv("CHATGPT_LOCAL_HOME")
        if (!envHome.isNullOrEmpty()) {
            candidates.add(Path.of(envHome, AUTH_FILENAME).toString())
        }

        val codexHome = System.getenv("CODEX_HOME")
        if (!codexHome.isNullOrEmpty()) {
            candidates.add(Path.of(codexHome, AUTH_FILENAME).toString())
        }

        val userHome = System.getProperty("user.home")
        candidates.add(Path.of(userHome, ".chatgpt-local", AUTH_FILENAME).toString())
        candidates.add(Path.of(userHome, ".codex", AUTH_FILENAME).toString())

        return ArrayList(candidates)
    }

    @JvmStatic
    fun resolveWritePath(preferred: String?): String {
        if (!preferred.isNullOrEmpty()) {
            return preferred
        }
        var envHome = System.getenv("CHATGPT_LOCAL_HOME")
        if (envHome.isNullOrEmpty()) {
            envHome = System.getenv("CODEX_HOME")
        }
        if (!envHome.isNullOrEmpty()) {
            return Path.of(envHome, AUTH_FILENAME).toString()
        }
        return Path.of(System.getProperty("user.home"), ".chatgpt-local", AUTH_FILENAME).toString()
    }
}
