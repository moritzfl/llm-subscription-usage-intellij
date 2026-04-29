package de.moritzf.quota.idea.kimi

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.kimi.KimiCredentials
import de.moritzf.quota.shared.JsonSupport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
class KimiCredentialsStore {
    private val attributes = CredentialAttributes("Kimi Credentials", "kimi-credentials")
    private val cachedCredentials = AtomicReference<KimiCredentials?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun load(onLoaded: (() -> Unit)? = null): KimiCredentials? {
        if (!loaded.get()) {
            if (onLoaded != null) loadCallbacks += onLoaded
            if (loading.compareAndSet(false, true)) loadAsync()
            return null
        }
        return cachedCredentials.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    fun loadBlocking(): KimiCredentials? {
        loadGeneration.incrementAndGet()
        val credentials = loadStoredCredentials()
        cachedCredentials.set(credentials)
        loaded.set(true)
        notifyLoadedCallbacks()
        return credentials
    }

    fun save(credentials: KimiCredentials) {
        loadGeneration.incrementAndGet()
        val json = JsonSupport.json.encodeToString(KimiCredentials.serializer(), credentials)
        PasswordSafe.instance.set(attributes, Credentials("kimi-credentials", json))
        cachedCredentials.set(credentials)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        PasswordSafe.instance.set(attributes, null)
        cachedCredentials.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val credentials = loadStoredCredentials()
                if (loadGeneration.get() == generation) {
                    cachedCredentials.set(credentials)
                    loaded.set(true)
                    notifyLoadedCallbacks()
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun loadStoredCredentials(): KimiCredentials? {
        val json = try { PasswordSafe.instance.get(attributes)?.getPasswordAsString() } catch (_: Exception) { null }
        if (json.isNullOrBlank()) return null
        return runCatching { JsonSupport.json.decodeFromString(KimiCredentials.serializer(), json) }.getOrNull()
    }

    private fun notifyLoadedCallbacks() {
        if (loadCallbacks.isEmpty()) return
        val callbacks = loadCallbacks.toList()
        loadCallbacks.clear()
        callbacks.forEach { ApplicationManager.getApplication().invokeLater(it) }
    }

    companion object {
        @JvmStatic
        fun getInstance(): KimiCredentialsStore = ApplicationManager.getApplication().getService(KimiCredentialsStore::class.java)
    }
}
