package de.moritzf.quota.idea.github

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.concurrency.AppExecutorUtil
import de.moritzf.quota.github.GitHubCredentials
import de.moritzf.quota.shared.JsonSupport
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
class GitHubCredentialsStore(
    private val userName: String = DEFAULT_USER,
    serviceName: String = SERVICE_NAME,
) {
    private val attributes = CredentialAttributes(serviceName, userName)
    private val cachedCredentials = AtomicReference<GitHubCredentials?>()
    private val loaded = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val loadGeneration = AtomicLong(0)
    private val loadCallbacks = CopyOnWriteArrayList<() -> Unit>()

    fun load(onLoaded: (() -> Unit)? = null): GitHubCredentials? {
        if (!loaded.get()) {
            if (onLoaded != null) loadCallbacks += onLoaded
            if (loading.compareAndSet(false, true)) loadAsync()
            return null
        }
        return cachedCredentials.get()
    }

    fun isLoaded(): Boolean = loaded.get()

    fun loadBlocking(): GitHubCredentials? {
        loadGeneration.incrementAndGet()
        val credentials = loadStoredCredentials()
        cachedCredentials.set(credentials)
        loaded.set(true)
        notifyLoadedCallbacks()
        return credentials
    }

    fun save(credentials: GitHubCredentials) {
        loadGeneration.incrementAndGet()
        val json = JsonSupport.json.encodeToString(GitHubCredentials.serializer(), credentials)
        PasswordSafe.instance.set(attributes, Credentials(userName, json))
        cachedCredentials.set(credentials)
        loaded.set(true)
        loading.set(false)
        notifyLoadedCallbacks()
    }

    fun clear() {
        loadGeneration.incrementAndGet()
        de.moritzf.quota.idea.auth.PasswordSafeSecrets.clear(attributes, "GitHub credentials")
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

    private fun loadStoredCredentials(): GitHubCredentials? {
        val json = try { PasswordSafe.instance.get(attributes)?.getPasswordAsString() } catch (_: Exception) { null }
        if (json.isNullOrBlank()) return null
        return runCatching { JsonSupport.json.decodeFromString(GitHubCredentials.serializer(), json) }.getOrNull()
    }

    private fun notifyLoadedCallbacks() {
        if (loadCallbacks.isEmpty()) return
        val callbacks = loadCallbacks.toList()
        loadCallbacks.clear()
        callbacks.forEach { ApplicationManager.getApplication().invokeLater(it) }
    }

    companion object {
        private const val SERVICE_NAME = "GitHub Copilot Credentials"
        private const val DEFAULT_USER = "github-copilot-credentials"
        private val extras = java.util.concurrent.ConcurrentHashMap<String, GitHubCredentialsStore>()

        fun getInstance(): GitHubCredentialsStore = ApplicationManager.getApplication().getService(GitHubCredentialsStore::class.java)

        fun forAccount(accountId: String): GitHubCredentialsStore =
            de.moritzf.quota.idea.settings.AccountCredentialKeys.store(
                accountId,
                de.moritzf.quota.idea.common.QuotaProviderType.GITHUB.id,
                SERVICE_NAME,
                DEFAULT_USER,
                extras,
                ::getInstance,
            ) { service, user -> GitHubCredentialsStore(userName = user, serviceName = service) }
    }
}
