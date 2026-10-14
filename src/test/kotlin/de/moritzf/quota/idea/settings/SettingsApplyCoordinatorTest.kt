package de.moritzf.quota.idea.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsApplyCoordinatorTest {
    @Test
    fun compatibleValidationPrecedesSecretWritesAndRemovalClearsOnlyRemovedAccount() {
        val row =
            ProviderAccount("api", "openai-compatible", "Custom").apply {
                setExtra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL, "https://example.com/v1")
            }
        val state = QuotaSettingsState().apply { accounts = mutableListOf(row) }
        val invalid =
            QuotaSettingsDraft.from(state).also {
                it.accounts
                    .single()
                    .setExtra(
                        ProviderAccount.EXTRA_COMPATIBLE_BASE_URL,
                        "https://example.com/v1/models",
                    )
            }
        val events = mutableListOf<String>()
        val coordinator = coordinator(state, events)
        assertFailsWith<IllegalArgumentException> {
            coordinator.apply(
                invalid,
                credentialsChanged = true,
                saveCredentials = { events += "save" },
            )
        }
        assertTrue(events.isEmpty())
        assertEquals(
            "https://example.com/v1",
            state.accounts.single().extra(ProviderAccount.EXTRA_COMPATIBLE_BASE_URL),
        )
        coordinator.apply(
            QuotaSettingsDraft.from(state),
            credentialsChanged = true,
            saveCredentials = { events += "save" },
        )
        assertEquals(listOf("save", "sync", "publish"), events)
        events.clear()
        coordinator.apply(QuotaSettingsDraft.from(state).copy(accounts = emptyList()))
        assertEquals(listOf("delete:api", "clear:api", "sync", "publish"), events)
        assertTrue(state.accounts.isEmpty())
    }

    @Test
    fun settingsEventReloadsEachServiceExactlyOnce() {
        val calls = mutableListOf<String>()
        SettingsServicesListener({ calls += "proxy" }, { calls += "mcp" }).onSettingsChanged()
        assertEquals(listOf("proxy", "mcp"), calls)
    }

    @Test
    fun applyPublishesOnceAndPreservesConcurrentQuotaUpdates() {
        val state =
            QuotaSettingsState().apply {
                accounts = mutableListOf(ProviderAccount("a", "openai", "A"))
            }
        val draft = QuotaSettingsDraft.from(state).copy(proxyEnabled = true)
        state.storeQuotaSnapshot("a", "new quota")
        val events = mutableListOf<String>()
        val coordinator = coordinator(state, events)
        assertTrue(coordinator.apply(draft))
        assertEquals(listOf("sync", "publish"), events)
        assertEquals("new quota", state.cachedQuotaJson("a"))
        events.clear()
        assertFalse(coordinator.apply(QuotaSettingsDraft.from(state)))
        assertTrue(events.isEmpty())
    }

    @Test
    fun invalidDraftHasNoSideEffectsAndResetDiscardsEdits() {
        val state =
            QuotaSettingsState().apply {
                accounts = mutableListOf(ProviderAccount("a", "openai", "A"))
            }
        val original = QuotaSettingsDraft.from(state)
        val draft = QuotaSettingsDraft.from(state)
        draft.accounts.single().name = ""
        val events = mutableListOf<String>()
        assertFailsWith<IllegalArgumentException> { coordinator(state, events).apply(draft) }
        assertEquals(original, QuotaSettingsDraft.from(state))
        assertTrue(events.isEmpty())
    }

    @Test
    fun removalAndProviderChangesAreHandledOutsideUi() {
        val state =
            QuotaSettingsState().apply {
                accounts =
                    mutableListOf(
                        ProviderAccount("a", "azure", "Azure"),
                        ProviderAccount("b", "openai", "B"),
                    )
            }
        val draft =
            QuotaSettingsDraft.from(state).let {
                it.copy(
                    accounts =
                        listOf(
                            it.accounts.first().apply {
                                setExtra(
                                    ProviderAccount.EXTRA_AZURE_ENDPOINT,
                                    "https://example.com",
                                )
                            }
                        )
                )
            }
        val events = mutableListOf<String>()
        coordinator(state, events).apply(draft)
        assertEquals(
            listOf("delete:b", "clear:b", "sync", "clear:a", "refresh:a", "publish"),
            events,
        )
    }

    private fun coordinator(state: QuotaSettingsState, events: MutableList<String>) =
        SettingsApplyCoordinator(
            state,
            clearSecrets = { events += "delete:${it.id}" },
            syncAccounts = { events += "sync" },
            clearUsage = { events += "clear:$it" },
            refresh = { events += "refresh:$it" },
            publish = { events += "publish" },
        )
}
