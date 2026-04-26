package de.moritzf.quota.idea

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.atomic.AtomicBoolean
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

    /**
     * Returns the cached cookie value, or null if not yet loaded.
     * Safe to call from the EDT.
     */
    fun load(): String? {
        if (!loaded.get()) {
            if (loading.compareAndSet(false, true)) {
                loadAsync()
            }
            return null
        }
        return cachedCookie.get()
    }

    /**
     * Loads the cookie from PasswordSafe on a background thread.
     */
    private fun loadAsync() {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val stored = try {
                    PasswordSafe.instance.get(attributes)
                } catch (exception: Exception) {
                    null
                }
                val cookie = stored?.getPasswordAsString()?.ifBlank { null }
                cachedCookie.set(cookie)
            } finally {
                loaded.set(true)
                loading.set(false)
            }
        }
    }

    /**
     * Forces a synchronous reload. Should NOT be called from the EDT.
     */
    fun loadBlocking(): String? {
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
        return cookie
    }

    fun save(cookie: String) {
        try {
            PasswordSafe.instance.set(attributes, Credentials(USER_NAME, cookie))
        } catch (exception: Exception) {
            throw IllegalStateException("Could not persist OpenCode session cookie", exception)
        }
        cachedCookie.set(cookie.ifBlank { null })
        loaded.set(true)
        loading.set(false)
        LOG.fine("Saved cookie (len=${cookie.length})")
    }

    fun clear() {
        try {
            PasswordSafe.instance.set(attributes, null)
        } catch (exception: Exception) {
            // ignore
        }
        cachedCookie.set(null)
        loaded.set(true)
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
