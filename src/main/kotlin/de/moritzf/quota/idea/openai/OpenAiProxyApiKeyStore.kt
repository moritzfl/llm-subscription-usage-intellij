package de.moritzf.quota.idea.openai

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
class OpenAiProxyApiKeyStore {
    private val attributes = CredentialAttributes(SERVICE_NAME, USER_NAME)
    private val cachedApiKey = AtomicReference<String?>()

    fun loadBlocking(): String? {
        cachedApiKey.get()?.let { return it }
        val apiKey = readPasswordSafe()
        cachedApiKey.set(apiKey)
        return apiKey
    }

    fun loadFreshBlocking(): String? {
        val apiKey = readPasswordSafe()
        cachedApiKey.set(apiKey)
        return apiKey
    }

    fun cachedApiKey(): String? {
        return cachedApiKey.get()
    }

    fun ensureApiKeyBlocking(): String {
        loadBlocking()?.let { return it }
        return regenerateBlocking()
    }

    fun regenerateBlocking(): String {
        val apiKey = generateApiKey()
        saveBlocking(apiKey)
        return apiKey
    }

    fun generateApiKeyForEditing(): String {
        return generateApiKey()
    }

    fun saveBlocking(apiKey: String?) {
        val sanitized = apiKey?.ifBlank { null }
        PasswordSafe.instance.set(attributes, sanitized?.let { Credentials(USER_NAME, it) })
        cachedApiKey.set(sanitized)
    }

    private fun generateApiKey(): String {
        val random = ByteArray(32)
        SECURE_RANDOM.nextBytes(random)
        return "sk-quota-${Base64.getUrlEncoder().withoutPadding().encodeToString(random)}"
    }

    private fun readPasswordSafe(): String? {
        return try {
            PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val SERVICE_NAME = "OpenAI Proxy API Key"
        private const val USER_NAME = "openai-proxy-api-key"
        private val SECURE_RANDOM = SecureRandom()

        @JvmStatic
        fun getInstance(): OpenAiProxyApiKeyStore {
            return ApplicationManager.getApplication().getService(OpenAiProxyApiKeyStore::class.java)
        }
    }
}
