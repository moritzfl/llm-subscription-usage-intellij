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
    fun rateLimitedReadsReturnTheLastGoodResponseMarkedStale() {
        val settings = QuotaSettingsState()
        settings.addAccount(QuotaProviderType.CLAUDE)
        val fetchedAt = kotlin.time.Instant.parse("2026-10-22T13:51:46Z")
        val good = """{"five_hour":{"utilization":40},"access_token":"secret-token"}"""
        var step = 0
        val provider =
            object : CachedQuotaProvider<ClaudeQuota>() {
                override val type = QuotaProviderType.CLAUDE

                override fun refresh() {
                    when (step++) {
                        0 -> storeQuota(ClaudeQuota(fetchedAt = fetchedAt), good)
                        1 ->
                            storeFetchFailure(
                                429,
                                "Claude usage API rate limited. Next try at 15:42.",
                                """{"type":"error","error":{"type":"rate_limit_error"}}""",
                            )
                        else -> storeQuota(ClaudeQuota(), """{"five_hour":{"utilization":5}}""")
                    }
                }
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
            operations.quotaResult(provider.type)

            val stale =
                JsonSupport.json
                    .parseToJsonElement(operations.quotaResult(provider.type))
                    .jsonObject
            val marker = stale.getValue("stale").jsonObject
            assertEquals("rate_limited", marker.getValue("reason").jsonPrimitive.content)
            assertEquals(
                "Claude usage API rate limited. Next try at 15:42.",
                marker.getValue("error").jsonPrimitive.content,
            )
            assertEquals(fetchedAt.toString(), marker.getValue("fetched_at").jsonPrimitive.content)
            val response = stale.getValue("response").jsonObject
            assertEquals(
                "40",
                response
                    .getValue("five_hour")
                    .jsonObject
                    .getValue("utilization")
                    .jsonPrimitive
                    .content,
            )
            assertFalse("secret-token" in stale.toString(), "credentials stay redacted")

            assertEquals(
                """{"five_hour":{"utilization":5}}""",
                operations.quotaResult(provider.type),
            )
        } finally {
            service.dispose()
            Disposer.dispose(disposable)
        }
    }

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
