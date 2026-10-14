package de.moritzf.quota.idea.common

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls private const val BUNDLE = "messages.OpenAiUsageQuotaBundle"

/** IDE-facing text; keep protocol values and provider payloads outside this bundle. */
internal object QuotaBundle {
    private val bundle = DynamicBundle(QuotaBundle::class.java, BUNDLE)

    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): @Nls String = bundle.getMessage(key, *params)
}
