package de.moritzf.quota.idea.common

import com.intellij.util.messages.Topic
import de.moritzf.quota.shared.ProviderQuota

/**
 * Message bus listener for quota refresh updates.
 */
interface QuotaUsageListener {
    fun onQuotaUpdated(type: QuotaProviderType, quota: ProviderQuota?, error: String?) {}

    companion object {
        @JvmField
        val TOPIC: Topic<QuotaUsageListener> = Topic.create("LLM Subscription Usage Updated", QuotaUsageListener::class.java)
    }
}
