package de.moritzf.quota.idea.common

import com.intellij.util.messages.Topic
import de.moritzf.quota.openai.OpenAiCodexQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.ollama.OllamaQuota
import de.moritzf.quota.zai.ZaiQuota
import de.moritzf.quota.minimax.MiniMaxQuota

/**
 * Message bus listener for quota refresh updates.
 */
interface QuotaUsageListener {
    fun onQuotaUpdated(quota: OpenAiCodexQuota?, error: String?) {}
    fun onOpenCodeQuotaUpdated(quota: OpenCodeQuota?, error: String?) {}
    fun onOllamaQuotaUpdated(quota: OllamaQuota?, error: String?) {}
    fun onZaiQuotaUpdated(quota: ZaiQuota?, error: String?) {}
    fun onMiniMaxQuotaUpdated(quota: MiniMaxQuota?, error: String?) {}

    companion object {
        @JvmField
        val TOPIC: Topic<QuotaUsageListener> = Topic.create("LLM Subscription Usage Updated", QuotaUsageListener::class.java)
    }
}
