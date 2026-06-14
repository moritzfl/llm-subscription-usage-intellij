package de.moritzf.quota.idea.ollama

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

@Service(Service.Level.APP)
class OllamaApiKeyStore {
    private val attributes = CredentialAttributes(SERVICE_NAME, USER_NAME)
    private val cachedApiKey = AtomicReference<String?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun load(onLoaded: (() -> Unit)? = null): String? {
        if (!loaded.get()) {
            if (onLoaded != null) {
                loadCallbacks += onLoaded
                if (loaded.get() && loadCallbacks.remove(onLoaded)) {
                    ApplicationManager.getApplication().invokeLater(onLoaded)
                    return cachedApiKey.get()
                }
            }
            if (loading.compareAndSet(false, true)) {
                loadAsync()
            }
            return null
        }
        return cachedApiKey.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    fun loadBlocking(): String? {
        loadGeneration.incrementAndGet()
        val apiKey = loadKey()
        cachedApiKey.set(apiKey)
        loaded.set(true)
        LOG.fine("Loaded Ollama API key: len=${apiKey?.length ?: 0}, present=${apiKey != null}")
        notifyLoadedCallbacks()
        return apiKey
    }

    fun save(apiKey: String?) {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(attributes, apiKey?.takeIf { it.isNotBlank() }?.let { Credentials(USER_NAME, it) })
        } catch (exception: Exception) {
            throw IllegalStateException("Could not persist Ollama API key", exception)
        }
        cachedApiKey.set(apiKey?.ifBlank { null })
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(attributes, null)
        } catch (_: Exception) {
            // ignore
        }
        cachedApiKey.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val apiKey = loadKey()
                if (loadGeneration.get() == generation) {
                    cachedApiKey.set(apiKey)
                    loaded.set(true)
                    notifyLoadedCallbacks()
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun loadKey(): String? {
        return try {
            PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.ifBlank { null }
        } catch (exception: Exception) {
            LOG.warning("Failed to load Ollama API key: ${exception.message}")
            null
        }
    }

    private fun notifyLoadedCallbacks() {
        if (loadCallbacks.isEmpty()) {
            return
        }
        val callbacks = loadCallbacks.toList()
        loadCallbacks.clear()
        callbacks.forEach { callback ->
            ApplicationManager.getApplication().invokeLater(callback)
        }
    }

    companion object {
        private const val SERVICE_NAME = "Ollama API Key"
        private const val USER_NAME = "ollama-api-key"
        private val LOG = Logger.getLogger(OllamaApiKeyStore::class.java.name)

        @JvmStatic
        fun getInstance(): OllamaApiKeyStore {
            return ApplicationManager.getApplication().getService(OllamaApiKeyStore::class.java)
        }
    }
}
