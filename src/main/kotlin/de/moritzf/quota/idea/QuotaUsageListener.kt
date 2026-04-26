package de.moritzf.quota.idea

import com.intellij.util.messages.Topic
import de.moritzf.quota.OpenAiCodexQuota
import de.moritzf.quota.OpenCodeQuota

/**
 * Message bus listener for quota refresh updates.
 */
interface QuotaUsageListener {
    fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {}
    fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {}

    companion object {
        @JvmField
        val TOPIC: Topic<QuotaUsageListener> = Topic.create("LLM Subscription Usage Updated", QuotaUsageListener::class.java)
    }
}
