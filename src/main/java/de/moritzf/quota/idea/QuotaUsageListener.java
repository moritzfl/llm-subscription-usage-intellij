package de.moritzf.quota.idea;

import com.intellij.util.messages.Topic;
import de.moritzf.quota.OpenAiCodexQuota;
import org.jetbrains.annotations.Nullable;

/**
 * Message bus listener for quota refresh updates.
 */
public interface QuotaUsageListener {
    Topic<QuotaUsageListener> TOPIC = Topic.create("OpenAI Usage Quota Updated", QuotaUsageListener.class);

    void onQuotaUpdated(@Nullable OpenAiCodexQuota quota, @Nullable String error);
}
