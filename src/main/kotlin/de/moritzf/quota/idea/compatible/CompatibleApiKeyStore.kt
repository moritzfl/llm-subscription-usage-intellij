package de.moritzf.quota.idea.compatible

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Plugin-owned keys, isolated by the account's stable ID. */
internal class CompatibleApiKeyStore private constructor(private val accountId: String) {
    private val attributes =
        CredentialAttributes(
            "LLM Subscription Usage OpenAI-compatible API key ($accountId)",
            accountId,
        )
    @Volatile private var key: String? = null
    @Volatile
    var isLoaded = false
        private set

    private val loading = AtomicBoolean()
    private val generation = AtomicLong()
    private val callbacks = CopyOnWriteArrayList<() -> Unit>()

    fun load(onLoaded: (() -> Unit)? = null): String? {
        if (isLoaded) return key
        if (onLoaded != null) callbacks.addIfAbsent(onLoaded)
        if (loading.compareAndSet(false, true)) {
            AppExecutorUtil.getAppExecutorService().execute {
                try {
                    loadBlocking()
                } finally {
                    loading.set(false)
                    val pending = callbacks.toList()
                    callbacks.clear()
                    val app = ApplicationManager.getApplication()
                    if (app != null && !app.isDisposed) app.invokeLater { pending.forEach { it() } }
                }
            }
        }
        return null
    }

    fun loadBlocking(): String? {
        if (isLoaded) return key
        val gen = generation.get()
        val loaded =
            PasswordSafe.instance.get(attributes)?.getPasswordAsString()?.takeIf(String::isNotBlank)
        synchronized(this) {
            if (generation.get() == gen) {
                key = loaded
                isLoaded = true
            }
            return key
        }
    }

    @Synchronized
    fun save(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
        PasswordSafe.instance.set(attributes, normalized?.let { Credentials(accountId, it) })
        generation.incrementAndGet()
        key = normalized
        isLoaded = true
    }

    fun clear() = save(null)

    companion object {
        private val stores = ConcurrentHashMap<String, CompatibleApiKeyStore>()

        fun forAccount(id: String): CompatibleApiKeyStore =
            stores.computeIfAbsent(id, ::CompatibleApiKeyStore)
    }
}
