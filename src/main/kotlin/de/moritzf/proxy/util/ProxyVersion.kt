package de.moritzf.proxy.util

import java.io.IOException
import java.util.Properties

/**
 * Resolves the proxy's version from the `aiproxyoauth-version.properties` resource
 * that the build generates from the Gradle `pluginVersion` property, so every surface
 * (health endpoint, CLI --version) reports the plugin version. Running without the
 * generated resource, e.g. straight from an IDE compile, yields "dev".
 */
object ProxyVersion {
    private const val VERSION_RESOURCE = "/aiproxyoauth-version.properties"
    private const val DEV_VERSION = "dev"
    private val version = resolve()

    @JvmStatic
    fun get(): String = version

    private fun resolve(): String {
        return try {
            ProxyVersion::class.java.getResourceAsStream(VERSION_RESOURCE)?.use { input ->
                val properties = Properties()
                properties.load(input)
                properties.getProperty("version")?.takeIf { it.isNotBlank() }?.trim()
            } ?: DEV_VERSION
        } catch (_: IOException) {
            DEV_VERSION
        }
    }
}
