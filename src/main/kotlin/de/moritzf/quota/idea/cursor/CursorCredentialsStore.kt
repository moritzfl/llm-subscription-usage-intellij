package de.moritzf.quota.idea.cursor

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.cursor.CursorAuth
import de.moritzf.quota.cursor.CursorSessionTokenParser
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/**
 * Resolves Cursor credentials from a browser session cookie.
 */
@Service(Service.Level.APP)
class CursorCredentialsStore {
    private val sessionAttributes = CredentialAttributes(SESSION_SERVICE_NAME, SESSION_USER_NAME)
    private val cachedSessionCookie = AtomicReference<String?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun load(onLoaded: (() -> Unit)? = null): CursorAuth? {
        if (!loaded.get()) {
            if (onLoaded != null) {
                loadCallbacks += onLoaded
            }
            if (loading.compareAndSet(false, true)) {
                loadAsync()
            }
            return null
        }
        return resolveAuth()
    }

    fun isLoaded(): Boolean = loaded.get()

    fun hasSessionCookie(): Boolean {
        if (loaded.get()) {
            return !cachedSessionCookie.get().isNullOrBlank()
        }
        return !loadSessionCookie().isNullOrBlank()
    }

    fun hasCredentials(): Boolean {
        if (!loaded.get()) {
            return !loadSessionCookie().isNullOrBlank()
        }
        return resolveAuth() != null
    }

    fun loadBlocking(): CursorAuth? {
        loadGeneration.incrementAndGet()
        cachedSessionCookie.set(loadSessionCookie())
        loaded.set(true)
        notifyLoadedCallbacks()
        return resolveAuth()
    }

    fun saveSessionCookie(sessionCookie: String) {
        loadGeneration.incrementAndGet()
        val normalized = sessionCookie.trim()
        require(normalized.isNotBlank()) { "session cookie must not be blank" }
        require(!CursorSessionTokenParser.extractAccessToken(normalized).isNullOrBlank()) {
            "Could not parse WorkosCursorSessionToken cookie"
        }
        try {
            PasswordSafe.instance.set(sessionAttributes, Credentials(SESSION_USER_NAME, normalized))
        } catch (exception: Exception) {
            throw IllegalStateException("Could not persist Cursor session cookie", exception)
        }
        cachedSessionCookie.set(normalized)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun clearSessionCookie() {
        loadGeneration.incrementAndGet()
        try {
            PasswordSafe.instance.set(sessionAttributes, null)
        } catch (exception: Exception) {
            // ignore
        }
        cachedSessionCookie.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val sessionCookie = loadSessionCookie()
                if (loadGeneration.get() == generation) {
                    cachedSessionCookie.set(sessionCookie)
                    loaded.set(true)
                    notifyLoadedCallbacks()
                }
            } finally {
                loading.set(false)
            }
        }
    }

    private fun loadSessionCookie(): String? {
        return try {
            PasswordSafe.instance.get(sessionAttributes)?.getPasswordAsString()?.ifBlank { null }
        } catch (exception: Exception) {
            LOG.warning("Failed to load Cursor session cookie: ${exception.message}")
            null
        }
    }

    private fun resolveAuth(): CursorAuth? {
        val sessionCookie = cachedSessionCookie.get() ?: return null
        val accessToken = CursorSessionTokenParser.extractAccessToken(sessionCookie) ?: return null
        return CursorAuth(
            accessToken = accessToken,
            sessionCookie = sessionCookie,
        )
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
        private const val SESSION_SERVICE_NAME = "Cursor Session Cookie"
        private const val SESSION_USER_NAME = "WorkosCursorSessionToken"
        private val LOG = Logger.getLogger(CursorCredentialsStore::class.java.name)

        @JvmStatic
        fun getInstance(): CursorCredentialsStore {
            return ApplicationManager.getApplication().getService(CursorCredentialsStore::class.java)
        }
    }
}
