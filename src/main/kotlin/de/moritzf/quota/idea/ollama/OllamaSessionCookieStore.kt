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

/**
 * Stores the Ollama session cookie (__Secure-session) and optional cf_clearance in IntelliJ PasswordSafe.
 * Caches the values in memory to avoid calling PasswordSafe on the EDT.
 */
@Service(Service.Level.APP)
class OllamaSessionCookieStore {
    private val sessionAttributes = CredentialAttributes(SESSION_SERVICE_NAME, SESSION_USER_NAME)
    private val cfClearanceAttributes = CredentialAttributes(CF_SERVICE_NAME, CF_USER_NAME)
    private val cachedSessionCookie = AtomicReference<String?>()
    private val cachedCfClearance = AtomicReference<String?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Returns the cached session cookie value, or null if not yet loaded.
     * Safe to call from the EDT.
     */
    fun loadSessionCookie(onLoaded: (() -> Unit)? = null): String? {
        if (!loaded.get()) {
            if (onLoaded != null) {
                loadCallbacks += onLoaded
                if (loaded.get() && loadCallbacks.remove(onLoaded)) {
                    ApplicationManager.getApplication().invokeLater(onLoaded)
                    return cachedSessionCookie.get()
                }
            }
            if (loading.compareAndSet(false, true)) {
                loadAsync()
            }
            return null
        }
        return cachedSessionCookie.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    /**
     * Loads the cookies from PasswordSafe on a background thread.
     */
    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val sessionStored = try {
                    PasswordSafe.instance.get(sessionAttributes)
                } catch (exception: Exception) {
                    null
                }
                val cfStored = try {
                    PasswordSafe.instance.get(cfClearanceAttributes)
                } catch (exception: Exception) {
                    null
                }
                val sessionCookie = sessionStored?.getPasswordAsString()?.ifBlank { null }
                val cfClearance = cfStored?.getPasswordAsString()?.ifBlank { null }
                if (loadGeneration.get() == generation) {
                    cachedSessionCookie.set(sessionCookie)
                    cachedCfClearance.set(cfClearance)
                    loaded.set(true)
                    notifyLoadedCallbacks()
                }
            } finally {
                loading.set(false)
            }
        }
    }

    /**
     * Forces a synchronous reload. Should NOT be called from the EDT.
     */
    fun loadBlocking(): Pair<String?, String?> {
        loadGeneration.incrementAndGet()
        val sessionStored = try {
            PasswordSafe.instance.get(sessionAttributes)
        } catch (exception: Exception) {
            LOG.warning("Failed to load Ollama session cookie: ${exception.message}")
            null
        }
        val cfStored = try {
            PasswordSafe.instance.get(cfClearanceAttributes)
        } catch (exception: Exception) {
            LOG.warning("Failed to load Ollama cf_clearance: ${exception.message}")
            null
        }
        val sessionCookie = sessionStored?.getPasswordAsString()?.ifBlank { null }
        val cfClearance = cfStored?.getPasswordAsString()?.ifBlank { null }
        cachedSessionCookie.set(sessionCookie)
        cachedCfClearance.set(cfClearance)
        loaded.set(true)
        LOG.fine("Loaded Ollama cookies: sessionLen=${sessionCookie?.length ?: 0}, cfLen=${cfClearance?.length ?: 0}")
        notifyLoadedCallbacks()
        return sessionCookie to cfClearance
    }

    fun save(sessionCookie: String, cfClearance: String?) {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(sessionAttributes, Credentials(SESSION_USER_NAME, sessionCookie))
            if (!cfClearance.isNullOrBlank()) {
                PasswordSafe.instance.set(cfClearanceAttributes, Credentials(CF_USER_NAME, cfClearance))
            } else {
                PasswordSafe.instance.set(cfClearanceAttributes, null)
            }
        } catch (exception: Exception) {
            throw IllegalStateException("Could not persist Ollama session cookies", exception)
        }
        cachedSessionCookie.set(sessionCookie.ifBlank { null })
        cachedCfClearance.set(cfClearance?.ifBlank { null })
        loaded.set(true)
        loading.set(false)
        LOG.fine("Saved Ollama cookies: sessionLen=${sessionCookie.length}, cfPresent=${!cfClearance.isNullOrBlank()}")
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(sessionAttributes, null)
            PasswordSafe.instance.set(cfClearanceAttributes, null)
        } catch (exception: Exception) {
            // ignore
        }
        cachedSessionCookie.set(null)
        cachedCfClearance.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun getCfClearance(): String? = cachedCfClearance.get()

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
        private const val SESSION_SERVICE_NAME = "Ollama Session Cookie"
        private const val SESSION_USER_NAME = "ollama-session"
        private const val CF_SERVICE_NAME = "Ollama CF Clearance"
        private const val CF_USER_NAME = "ollama-cf"
        private val LOG = Logger.getLogger(OllamaSessionCookieStore::class.java.name)

        @JvmStatic
        fun getInstance(): OllamaSessionCookieStore {
            return ApplicationManager.getApplication().getService(OllamaSessionCookieStore::class.java)
        }
    }
}
