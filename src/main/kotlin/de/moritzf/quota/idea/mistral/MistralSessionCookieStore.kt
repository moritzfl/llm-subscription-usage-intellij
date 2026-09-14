package de.moritzf.quota.idea.mistral

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
class MistralSessionCookieStore(
    private val userName: String = DEFAULT_USER,
    serviceName: String = SERVICE_NAME,
) {
    private val attributes = CredentialAttributes(serviceName, userName)
    private val cachedCookie = AtomicReference<String?>()
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
        return cachedCookie.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    fun loadBlocking(): String? {
        loadGeneration.incrementAndGet()
        val cookie = loadCookie(attributes)
        cachedCookie.set(cookie)
        loaded.set(true)
        notifyLoadedCallbacks()
        return cookie
    }

    fun save(cookie: String?) {
        loadGeneration.incrementAndGet()
        PasswordSafe.instance.set(attributes, cookie?.takeIf { it.isNotBlank() }?.let { Credentials(userName, it) })
        cachedCookie.set(cookie?.ifBlank { null })
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        de.moritzf.quota.idea.auth.PasswordSafeSecrets.clear(attributes, "Mistral session cookie")
        cachedCookie.set(null)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    private fun loadAsync() {
        val generation = loadGeneration.get()
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val cookie = loadCookie(attributes)
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

    private fun loadCookie(attributes: CredentialAttributes): String? {
        return try { PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.ifBlank { null } } catch (_: Exception) { null }
    }

    private fun notifyLoadedCallbacks() {
        if (loadCallbacks.isEmpty()) return
        val callbacks = loadCallbacks.toList()
        loadCallbacks.clear()
        callbacks.forEach { ApplicationManager.getApplication().invokeLater(it) }
    }

    companion object {
        private const val SERVICE_NAME = "Mistral Session Cookie"
        private const val DEFAULT_USER = "mistral-session-cookie"
        private val extras = java.util.concurrent.ConcurrentHashMap<String, MistralSessionCookieStore>()

        @JvmStatic
        fun getInstance(): MistralSessionCookieStore =
            ApplicationManager.getApplication().getService(MistralSessionCookieStore::class.java)

        fun forAccount(accountId: String): MistralSessionCookieStore =
            de.moritzf.quota.idea.settings.AccountCredentialKeys.store(
                accountId,
                de.moritzf.quota.idea.common.QuotaProviderType.MISTRAL.id,
                SERVICE_NAME,
                DEFAULT_USER,
                extras,
                ::getInstance,
            ) { service, user -> MistralSessionCookieStore(userName = user, serviceName = service) }
    }
}
