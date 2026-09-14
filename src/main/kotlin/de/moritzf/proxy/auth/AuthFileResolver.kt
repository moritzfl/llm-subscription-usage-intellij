package de.moritzf.proxy.auth

object AuthFileResolver {
    fun resolveCandidates(authFilePath: String?): List<String> {
        val path = authFilePath?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(path)
    }
}
