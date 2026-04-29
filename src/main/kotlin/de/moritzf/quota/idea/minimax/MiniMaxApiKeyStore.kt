package de.moritzf.quota.idea.minimax

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

@Service(Service.Level.APP)
class MiniMaxApiKeyStore {
    private val attributes = CredentialAttributes("MiniMax API Key", "minimax-api-key")
    private val cachedApiKey = AtomicReference<String?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun load(onLoaded: (() -> Unit)? = null): String? {
        if (!loaded.get()) {
            if (onLoaded != null) loadCallbacks += onLoaded
            if (loading.compareAndSet(false, true)) loadAsync()
            return null
        }
        return cachedApiKey.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    fun loadBlocking(): String? {
        loadGeneration.incrementAndGet()
        val apiKey = loadKey(attributes)
        cachedApiKey.set(apiKey)
        loaded.set(true)
        notifyLoadedCallbacks()
        return apiKey
    }

    fun save(apiKey: String?) {
        loadGeneration.incrementAndGet()
        PasswordSafe.instance.set(attributes, apiKey?.takeIf { it.isNotBlank() }?.let { Credentials("minimax-api-key", it) })
        cachedApiKey.set(apiKey?.ifBlank { null })
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        PasswordSafe.instance.set(attributes, null)
        cachedApiKey.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val apiKey = loadKey(attributes)
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

    private fun loadKey(attributes: CredentialAttributes): String? {
        return try { PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.ifBlank { null } } catch (_: Exception) { null }
    }

    private fun notifyLoadedCallbacks() {
        if (loadCallbacks.isEmpty()) return
        val callbacks = loadCallbacks.toList()
        loadCallbacks.clear()
        callbacks.forEach { ApplicationManager.getApplication().invokeLater(it) }
    }

    companion object {
        @JvmStatic
        fun getInstance(): MiniMaxApiKeyStore = ApplicationManager.getApplication().getService(MiniMaxApiKeyStore::class.java)
    }
}
