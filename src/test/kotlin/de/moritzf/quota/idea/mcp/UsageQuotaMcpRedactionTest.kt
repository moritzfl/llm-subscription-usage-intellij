package de.moritzf.quota.idea.mcp

import de.moritzf.quota.cursor.CursorQuotaClient
import de.moritzf.quota.idea.common.ProviderCatalog
import de.moritzf.quota.idea.common.QuotaProvider
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.common.QuotaSnapshotCache
import de.moritzf.quota.idea.common.QuotaUsageService
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.mistral.MistralQuota
import de.moritzf.quota.opencode.OpenCodeQuota
import de.moritzf.quota.opencode.OpenCodeQuotaClient
import de.moritzf.quota.shared.JsonSupport
import de.moritzf.quota.supergrok.SuperGrokQuotaClient
import de.moritzf.quota.supergrok.SuperGrokResetToken
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class UsageQuotaMcpRedactionTest {
    @Test
    fun everyCatalogExportRedactsAccountResponsesAndFallbacks() {
        for (descriptor in ProviderCatalog.all.filter { it.mcpQuota != null }) {
            val raw =
                """{"access_token":"synthetic-secret","info":{"spend":12.5},"total_tokens":123}"""
            withService(descriptor.type, raw) { service ->
                val registration = assertNotNull(descriptor.mcpQuota)
                for (accountId in listOf("selected", "missing")) {
                    val result =
                        assertNotNull(
                            registration.jsonForAccount(service, descriptor.type, accountId)
                        )
                    assertFalse(result.contains("synthetic-secret"), descriptor.type.name)
                    assertTrue(result.contains("12.5"), descriptor.type.name)
                    JsonSupport.json.parseToJsonElement(result)
                }
            }
        }
    }

    @Test
    fun cursorEmbeddedDocumentsRemainStringsAndAreSanitized() {
        val raw =
            CursorQuotaClient.buildRawJson(
                """{"total_tokens":123}""",
                null,
                """{"accessToken":"synthetic-secret","id":"user-id"}""",
            )
        val result = export(QuotaProviderType.CURSOR, raw)
        assertFalse(result.contains("synthetic-secret"))
        val root = JsonSupport.json.parseToJsonElement(result).jsonObject
        assertTrue(root.getValue("profile").jsonPrimitive.isString)
        assertTrue(root.getValue("profile").jsonPrimitive.content.contains("user-id"))
        assertEquals("""{"total_tokens":123}""", root.getValue("periodUsage").jsonPrimitive.content)
    }

    @Test
    fun safeNativeResponsesIncludingMistralMetadataAndGrokResetIdsStayUnchanged() {
        val responses =
            mapOf(
                QuotaProviderType.MISTRAL to
                    """{ "session": {"remaining":12}, "api_key": {"identity":{"api_key":{"id":"key-id","name":"Work","future_metadata":{"quota":42}}},"rate_limits":{"remaining":42}} }""",
                QuotaProviderType.LITELLM to
                    """{ "key":"key-hash", "info":{"token":"token-hash","metadata":{"team":"engineering"},"config":{"region":"eu"},"spend":{"amount":12.5},"future_pools":[{"name":"fable-5","remaining":17}]} }""",
                QuotaProviderType.CLAUDE to
                    """{ "five_hour": {"utilization":12}, "seven_day_fable_5":{"utilization":42,"resets_at":"2030-10-01T00:00:00Z"} }""",
                QuotaProviderType.SUPERGROK to
                    SuperGrokQuotaClient.combinedRawJson(
                        """{"remaining":12}""",
                        null,
                        listOf(SuperGrokResetToken("reset-id")),
                    ),
                QuotaProviderType.OPEN_AI to
                    """{ "account_id": "account-id", "rate_limit": {"allowed":true} }""",
            )
        for ((type, raw) in responses) assertEquals(raw, export(type, raw))
        val mistral = responses.getValue(QuotaProviderType.MISTRAL)
        assertEquals(
            mistral,
            assertNotNull(
                    QuotaSnapshotCache.decode(
                        QuotaProviderType.MISTRAL,
                        QuotaSnapshotCache.encode(
                            QuotaProviderType.MISTRAL,
                            MistralQuota(rawJson = mistral),
                        ),
                    )
                )
                .rawJson,
        )
    }

    @Test
    fun unparseableNonJsonDiagnosticsReturnJsonErrorsWithoutRawBodies() {
        val result =
            export(QuotaProviderType.ZAI, "upstream: api_key=synthetic-secret; retry later")
        assertFalse(result.contains("synthetic-secret"))
        val message =
            JsonSupport.json
                .parseToJsonElement(result)
                .jsonObject
                .getValue("error")
                .jsonPrimitive
                .content
        assertEquals("Provider response could not be parsed as JSON.", message)
    }

    @Test
    fun failedOptionalSourcesKeepTheirOriginalBodyInSettingsAndUsableSiblingJsonInMcp() {
        val raw =
            assertNotNull(
                OpenCodeQuotaClient.buildRawResponse(
                    """{"new_pool":{"remaining":42}}""",
                    "<html>Gateway unavailable</html>",
                )
            )
        val settings = JsonSupport.json.parseToJsonElement(raw).jsonObject
        assertEquals(
            "<html>Gateway unavailable</html>",
            settings.getValue("billing").jsonPrimitive.content,
        )
        val exported =
            JsonSupport.json.parseToJsonElement(export(QuotaProviderType.OPEN_CODE, raw)).jsonObject
        assertEquals(settings.getValue("go"), exported.getValue("go"))
        assertTrue(exported.getValue("billing").jsonObject.containsKey("error"))
        assertFalse(exported.toString().contains("raw_response"))
        assertFalse(exported.toString().contains("<html>"))
    }

    private fun export(type: QuotaProviderType, raw: String): String =
        withService(type, raw) { service ->
            assertNotNull(
                assertNotNull(UsageQuotaMcpRegistry.get(type))
                    .jsonForAccount(service, type, "selected")
            )
        }

    private fun <T> withService(
        type: QuotaProviderType,
        raw: String,
        action: (QuotaUsageService) -> T,
    ): T {
        val settings = QuotaSettingsState()
        settings.addAccount(type).id = "selected"
        val provider =
            object : QuotaProvider {
                override val type = type
                override val accountId = "selected"

                override fun refresh() = Unit

                override fun clearData(error: String?) = Unit

                override fun getLastRawJson() = raw

                override fun getLastQuota() =
                    if (type == QuotaProviderType.OPEN_CODE) OpenCodeQuota(rawJson = raw) else null
            }
        val service =
            QuotaUsageService(
                providers = listOf(provider),
                settingsProvider = { settings },
                updatePublisher = {},
                scheduleOnInit = false,
            )
        try {
            return action(service)
        } finally {
            service.dispose()
        }
    }
}
