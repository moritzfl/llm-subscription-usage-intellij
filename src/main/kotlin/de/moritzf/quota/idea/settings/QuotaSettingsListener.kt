package de.moritzf.quota.idea.settings

import com.intellij.util.messages.Topic

/**
 * Broadcast when plugin settings affecting UI rendering have changed.
 */
fun interface QuotaSettingsListener {
    fun onSettingsChanged()

    companion object {
        @JvmField
        val TOPIC: Topic<QuotaSettingsListener> = Topic.create("openai.usage.quota.settings", QuotaSettingsListener::class.java)
    }
}
