package de.moritzf.quota.idea.settings

import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import de.moritzf.quota.idea.common.QuotaProviderType
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuotaSettingsMigrationsTest {
    private fun deserialize(xml: String): QuotaSettingsState =
        XmlSerializer.deserialize(JDOMUtil.load(xml), QuotaSettingsState::class.java)

    @Test
    fun runsMigrationForStateBelowItsVersion() {
        val state = QuotaSettingsState()
        val migration = RecordingMigration(version = 1)

        QuotaSettingsMigrations.run(state, migrations = listOf(migration))

        assertEquals(1, migration.applyCount)
        assertEquals(QuotaSettingsMigrations.CURRENT_VERSION, state.settingsVersion)
    }

    @Test
    fun skipsMigrationAlreadyCoveredByTheRecordedVersion() {
        val state = QuotaSettingsState().apply { settingsVersion = 2 }
        val applied = RecordingMigration(version = 2)
        val older = RecordingMigration(version = 1)

        QuotaSettingsMigrations.run(state, migrations = listOf(applied, older))

        assertEquals(0, applied.applyCount)
        assertEquals(0, older.applyCount)
    }

    @Test
    fun runsOnlyMigrationsNewerThanTheRecordedVersion() {
        val state = QuotaSettingsState().apply { settingsVersion = 2 }
        val old = RecordingMigration(version = 1)
        val pending = RecordingMigration(version = 3)

        QuotaSettingsMigrations.run(state, migrations = listOf(pending, old))

        assertEquals(0, old.applyCount)
        assertEquals(1, pending.applyCount)
    }

    @Test
    fun appliesMigrationsInVersionOrder() {
        val state = QuotaSettingsState()
        val order = mutableListOf<Int>()
        val third = RecordingMigration(version = 3, onApply = { order += 3 })
        val first = RecordingMigration(version = 1, onApply = { order += 1 })
        val second = RecordingMigration(version = 2, onApply = { order += 2 })

        QuotaSettingsMigrations.run(state, migrations = listOf(third, first, second))

        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun keepsAHigherVersionWrittenByANewerPlugin() {
        val state = QuotaSettingsState().apply { settingsVersion = 99 }

        QuotaSettingsMigrations.run(state, migrations = emptyList())

        assertEquals(99, state.settingsVersion, "a newer plugin's version must not be lowered")
    }

    @Test
    fun failingMigrationDoesNotStopTheOthers() {
        val state = QuotaSettingsState()
        val failing = RecordingMigration(version = 1, onApply = { error("migration failed") })
        val following = RecordingMigration(version = 1)

        QuotaSettingsMigrations.run(state, migrations = listOf(failing, following))

        assertEquals(1, failing.applyCount)
        assertEquals(1, following.applyCount)
        assertEquals(0, state.settingsVersion)
    }

    @Test
    fun failedMigrationDoesNotAdvanceSettingsVersion() {
        val state = QuotaSettingsState().apply { settingsVersion = 2 }
        val failing = RecordingMigration(version = 3, onApply = { error("migration failed") })

        QuotaSettingsMigrations.run(state, migrations = listOf(failing))

        assertEquals(1, failing.applyCount)
        assertEquals(2, state.settingsVersion)
    }

    @Test
    fun settingsWrittenBeforeMigrationsExistedStartAtVersionZero() {
        // Settings file from a plugin version that did not know settingsVersion yet.
        val legacy =
            deserialize(
                """
                <component name="OpenAiUsageQuotaSettings">
                  <option name="refreshMinutes" value="7" />
                  <option name="githubEnterpriseHost" value="ghe.example.com" />
                </component>
                """
                    .trimIndent()
            )

        assertEquals(0, legacy.settingsVersion)

        val state = QuotaSettingsState()
        state.loadState(legacy)

        assertEquals(7, state.refreshMinutes, "legacy values still load")
        assertEquals(QuotaSettingsMigrations.CURRENT_VERSION, state.settingsVersion)
    }

    @Test
    fun recordedVersionSurvivesASettingsRoundTrip() {
        val state =
            QuotaSettingsState().apply { settingsVersion = QuotaSettingsMigrations.CURRENT_VERSION }

        val reloaded = deserialize(JDOMUtil.write(XmlSerializer.serialize(state.state)))

        assertEquals(QuotaSettingsMigrations.CURRENT_VERSION, reloaded.settingsVersion)
    }

    @Test
    fun freshInstallRecordsTheCurrentVersionWithoutMigrating() {
        val state = QuotaSettingsState()

        state.noStateLoaded()

        assertEquals(QuotaSettingsMigrations.CURRENT_VERSION, state.settingsVersion)
    }

    @Test
    fun registeredMigrationsAreUniqueAndCoveredByTheCurrentVersion() {
        val ids = QuotaSettingsMigrations.ALL.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertTrue(
            QuotaSettingsMigrations.ALL.all {
                it.version in 1..QuotaSettingsMigrations.CURRENT_VERSION
            },
            "every migration needs a version that CURRENT_VERSION covers",
        )
    }

    @Test
    fun normalizeIndicatorSourceStorageIdsRewritesEnumNamesToProviderIds() {
        val state =
            QuotaSettingsState().apply {
                settingsVersion = 1
                indicatorSource = "OPEN_CODE"
                lastActiveSource = "OPEN_AI"
            }

        QuotaSettingsMigrations.run(state)

        assertEquals(QuotaIndicatorSource.OPEN_CODE.storageId, state.indicatorSource)
        assertEquals(QuotaProviderType.OPEN_AI.id, state.lastActiveSource)
        assertEquals(QuotaProviderType.OPEN_CODE, state.source().providerType)
        assertEquals(QuotaProviderType.OPEN_AI, state.lastActiveProvider())
    }

    @Test
    fun normalizeIndicatorSourceStorageIdsKeepsCanonicalIdsAndClearsJunk() {
        val state =
            QuotaSettingsState().apply {
                settingsVersion = 1
                indicatorSource = "last_used"
                lastActiveSource = "opencode"
            }
        QuotaSettingsMigrations.run(state)
        assertEquals(QuotaIndicatorSource.LAST_USED_ID, state.indicatorSource)
        assertEquals(QuotaProviderType.OPEN_CODE.id, state.lastActiveSource)

        val junk =
            QuotaSettingsState().apply {
                settingsVersion = 1
                lastActiveSource = "not-a-provider"
            }
        QuotaSettingsMigrations.run(junk)
        assertNull(junk.lastActiveSource)
    }

    @Test
    fun createProviderAccountsMigratesConfiguredTypesOnly() {
        val state =
            QuotaSettingsState().apply {
                settingsVersion = 2
                providerOrder = "openai,claude,mistral"
                hiddenFromQuotaPopup = mutableListOf("claude")
                cachedQuotaJsons = mutableMapOf("openai" to """{"ok":true}""")
                githubEnterpriseHost = "ghe.example.com"
                openCodeWorkspaceId = "ws-1"
                minimaxRegionPreference = "CN"
            }

        CreateProviderAccounts.apply(state) { type ->
            type == QuotaProviderType.CLAUDE || type == QuotaProviderType.GITHUB
        }

        assertEquals(
            listOf("openai", "claude", "github"),
            state.accounts.map { it.id },
        )
        assertEquals("OpenAI", state.accounts[0].name)
        assertTrue(state.accounts[0].isDefault)
        assertTrue(state.account("claude")!!.hiddenFromPopup)
        assertTrue(state.hiddenFromQuotaPopup.isEmpty())
        assertEquals(
            "ghe.example.com",
            state.account("github")!!.extra(ProviderAccount.EXTRA_GITHUB_HOST),
        )
        assertTrue(state.accounts.none { it.typeId == QuotaProviderType.MISTRAL.id })
    }

    @Test
    fun createProviderAccountsIsIdempotentWhenAccountsExist() {
        val state =
            QuotaSettingsState().apply {
                settingsVersion = 2
                accounts =
                    mutableListOf(
                        ProviderAccount.create(QuotaProviderType.ZAI, "Keep", isFirstOfType = true)
                    )
            }

        CreateProviderAccounts.apply(state) { true }

        assertEquals(listOf("zai"), state.accounts.map { it.id })
        assertEquals("Keep", state.accounts.single().name)
    }

    @Test
    fun createProviderAccountsLeavesEmptyListWhenNothingConfigured() {
        val state = QuotaSettingsState().apply { settingsVersion = 2 }

        CreateProviderAccounts.apply(state) { false }

        assertTrue(state.accounts.isEmpty())
    }

    private class RecordingMigration(
        override val version: Int,
        private val onApply: () -> Unit = {},
        override val id: String = "test-migration-$version",
    ) : QuotaSettingsMigration {
        var applyCount: Int = 0

        override fun apply(state: QuotaSettingsState) {
            applyCount++
            onApply()
        }
    }
}
