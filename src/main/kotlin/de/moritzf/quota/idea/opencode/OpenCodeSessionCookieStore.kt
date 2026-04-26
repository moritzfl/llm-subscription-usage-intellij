package de.moritzf.quota.idea.opencode

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
 * Stores the OpenCode session cookie in IntelliJ PasswordSafe.
 * Caches the value in memory to avoid calling PasswordSafe on the EDT.
 */
@Service(Service.Level.APP)
class OpenCodeSessionCookieStore {
    private val attributes = CredentialAttributes(SERVICE_NAME, USER_NAME)
    private val cachedCookie = AtomicReference<String?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Returns the cached cookie value, or null if not yet loaded.
     * Safe to call from the EDT.
     */
    fun load(onLoaded: (() -> Unit)? = null): String? {
        if (!loaded.get()) {
            if (onLoaded != null) {
                loadCallbacks += onLoaded
                if (loaded.get() && loadCallbacks.remove(onLoaded)) {
                    ApplicationManager.getApplication().invokeLater(onLoaded)
                    return cachedCookie.get()
                }
            }
            if (loading.compareAndSet(false, true)) {
                loadAsync()
            }
            return null
        }
        return cachedCookie.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    /**
     * Loads the cookie from PasswordSafe on a background thread.
     */
    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val stored = try {
                    PasswordSafe.instance.get(attributes)
                } catch (exception: Exception) {
                    null
                }
                val cookie = stored?.getPasswordAsString()?.ifBlank { null }
                if (loadGeneration.get() == generation) {
                    cachedCookie.set(cookie)
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
    fun loadBlocking(): String? {
        loadGeneration.incrementAndGet()
        val stored = try {
            PasswordSafe.instance.get(attributes)
        } catch (exception: Exception) {
            LOG.warning("Failed to load cookie: ${exception.message}")
            return null
        }
        val cookie = stored?.getPasswordAsString()?.ifBlank { null }
        cachedCookie.set(cookie)
        loaded.set(true)
        LOG.fine("Loaded cookie: len=${cookie?.length ?: 0}, present=${cookie != null}")
        notifyLoadedCallbacks()
        return cookie
    }

    fun save(cookie: String) {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(attributes, Credentials(USER_NAME, cookie))
        } catch (exception: Exception) {
            throw IllegalStateException("Could not persist OpenCode session cookie", exception)
        }
        cachedCookie.set(cookie.ifBlank { null })
        loaded.set(true)
        loading.set(false)
        LOG.fine("Saved cookie (len=${cookie.length})")
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(attributes, null)
        } catch (exception: Exception) {
            // ignore
        }
        cachedCookie.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
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
        private const val SERVICE_NAME = "OpenCode Session Cookie"
        private const val USER_NAME = "opencode-session"
        private val LOG = Logger.getLogger(OpenCodeSessionCookieStore::class.java.name)

        @JvmStatic
        fun getInstance(): OpenCodeSessionCookieStore {
            return ApplicationManager.getApplication().getService(OpenCodeSessionCookieStore::class.java)
        }
    }
}
