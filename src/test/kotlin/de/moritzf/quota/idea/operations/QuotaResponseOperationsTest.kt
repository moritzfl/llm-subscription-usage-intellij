package de.moritzf.quota.idea.operations

import com.intellij.mock.MockApplication
import com.intellij.openapi.util.Disposer
import de.moritzf.quota.claude.ClaudeQuota
import de.moritzf.quota.idea.common.CachedQuotaProvider
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.shared.JsonSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class QuotaResponseOperationsTest {
    @Test
    fun mcpKeepsFreshResponsesWhenUiParsingFailsButDoesNotMistakeOldDataForAFreshResponse() {
        val settings = QuotaSettingsState()
        settings.addAccount(QuotaProviderType.CLAUDE)
        var raw: String? =
            """{ "seven_day_fable_5": {"remaining":42}, "future_shape":[1,null,true] }"""
        val provider =
            object : CachedQuotaProvider<ClaudeQuota>() {
                override val type = QuotaProviderType.CLAUDE

                override fun refresh() = storeError("UI could not parse quota", raw)
            }
        val service =
            QuotaUsageService(
                providers = listOf(provider),
                settingsProvider = { settings },
                updatePublisher = {},
                scheduleOnInit = false,
            )
        val disposable = Disposer.newDisposable()
        val app = MockApplication.setUp(disposable)
        app.registerService(QuotaSettingsState::class.java, settings)
        app.registerService(QuotaUsageService::class.java, service)
        val operations = SubscriptionOperations()
        try {
            assertEquals(raw, operations.quotaResult(provider.type))
            assertTrue(provider.hasResponseForLastRefresh())

            raw = "<html>Gateway unavailable</html>"
            val wrapped =
                JsonSupport.json
                    .parseToJsonElement(operations.quotaResult(provider.type))
                    .jsonObject
            assertEquals(
                "Provider response could not be parsed as JSON.",
                wrapped.getValue("error").jsonPrimitive.content,
            )
            assertEquals(raw, provider.getLastRawJson())

            raw = null
            val failure =
                JsonSupport.json
                    .parseToJsonElement(operations.quotaResult(provider.type))
                    .jsonObject
            assertEquals(
                "UI could not parse quota",
                failure.getValue("error").jsonPrimitive.content,
            )
            assertFalse(provider.hasResponseForLastRefresh())
            assertEquals("<html>Gateway unavailable</html>", provider.getLastRawJson())
        } finally {
            service.dispose()
            Disposer.dispose(disposable)
        }
    }
}
