package de.moritzf.quota.idea.common

import de.moritzf.quota.idea.mcp.ImageEditProvider
import de.moritzf.quota.idea.mcp.ImageGenerationProvider
import de.moritzf.quota.idea.mcp.ListSearchProvider
import de.moritzf.quota.idea.mcp.SpeechToTextProvider
import de.moritzf.quota.idea.mcp.DocumentToMarkdownProvider
import de.moritzf.quota.idea.mcp.TextToSpeechProvider
import de.moritzf.quota.idea.mcp.VideoGenerationProvider
import de.moritzf.quota.idea.mcp.WebFetchProvider
import de.moritzf.quota.idea.mcp.UsageQuotaMcpRegistry
import de.moritzf.quota.idea.settings.ProviderSettingsRegistry
import de.moritzf.quota.idea.settings.QuotaSettingsState
import de.moritzf.quota.idea.ui.indicator.ProviderUiRegistry
import de.moritzf.quota.idea.ui.indicator.QuotaIndicatorSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderCatalogTest {
    @Test
    fun antigravityIsQuotaOnly() {
        val descriptor = ProviderCatalog.get(QuotaProviderType.ANTIGRAVITY)
        assertEquals(ProviderCapabilities(multipleAccounts = false), descriptor.capabilities)
    }

    @Test
    fun cliExceptionsAllowOnlyOneEntry() {
        assertEquals(
            listOf(QuotaProviderType.ANTIGRAVITY, QuotaProviderType.AZURE),
            ProviderCatalog.all.filter { !it.capabilities.multipleAccounts }.map { it.type },
        )
        val azure = ProviderCatalog.get(QuotaProviderType.AZURE)
        assertEquals(true, azure.capabilities.subscriptionProxy)
        assertEquals(false, azure.capabilities.multipleAccounts)
    }

    @Test
    fun coversEveryQuotaProviderType() {
        assertEquals(QuotaProviderType.entries.toSet(), ProviderCatalog.all.map { it.type }.toSet())
    }

    @Test
    fun facadesExposeSameProviderSet() {
        val catalog = ProviderCatalog.all.map { it.type }.toSet()
        assertEquals(catalog, QuotaProviderRegistry.all.map { it.type }.toSet())
        assertEquals(catalog, ProviderSettingsRegistry.all.keys)
        assertEquals(catalog, ProviderUiRegistry.all.keys)
        assertEquals(catalog, UsageQuotaMcpRegistry.all.keys)
    }

    @Test
    fun proxySupportedMatchesCapabilities() {
        val fromCaps = ProviderCatalog.all.filter { it.capabilities.subscriptionProxy }.map { it.type }
        assertEquals(fromCaps, ProviderCatalog.proxySupportedProviders())
        assertEquals(fromCaps, QuotaSettingsState.SUBSCRIPTION_PROXY_SUPPORTED_PROVIDERS)
        assertTrue(QuotaProviderType.CLAUDE !in fromCaps)
        assertTrue(QuotaProviderType.CURSOR !in fromCaps)
        assertTrue(QuotaProviderType.OPEN_AI in fromCaps)
    }

    @Test
    fun mcpSearchEnumsMatchCapabilities() {
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.webSearch == WebSearchCapability.LIST }.map { it.type }.toSet(),
            ListSearchProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.webFetch }.map { it.type }.toSet(),
            WebFetchProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.imageGeneration }.map { it.type }.toSet(),
            ImageGenerationProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.imageEdit }.map { it.type }.toSet(),
            ImageEditProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.videoGeneration }.map { it.type }.toSet(),
            VideoGenerationProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.speechToText }.map { it.type }.toSet(),
            SpeechToTextProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.textToSpeech }.map { it.type }.toSet(),
            TextToSpeechProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.documentToMarkdown }.map { it.type }.toSet(),
            DocumentToMarkdownProvider.entries.map { it.providerType }.toSet(),
        )
        assertEquals(
            ProviderCatalog.all.filter { it.capabilities.webSearch == WebSearchCapability.ANSWER }.map { it.type }.toSet(),
            setOf(QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK, QuotaProviderType.MISTRAL),
        )
    }

    @Test
    fun oauthProvidersMatchCapability() {
        assertEquals(
            setOf(QuotaProviderType.CLAUDE, QuotaProviderType.OPEN_AI, QuotaProviderType.SUPERGROK),
            ProviderCatalog.oauthProviders().toSet(),
        )
    }

    @Test
    fun subscriptionProxyProvidersHaveIdeFactories() {
        val proxyTypes = ProviderCatalog.proxySupportedProviders().toSet()
        assertEquals(
            proxyTypes,
            ProviderCatalog.all.filter { it.ideProxyFactory != null }.map { it.type }.toSet(),
        )
        assertTrue(proxyTypes.isNotEmpty())
    }

    @Test
    fun indicatorSourcesShareProviderStorageIds() {
        val providerSources = QuotaIndicatorSource.entries.filter { it.providerType != null }
        assertEquals(QuotaProviderType.entries.toSet(), providerSources.map { it.providerType }.toSet())
        for (source in providerSources) {
            val type = checkNotNull(source.providerType)
            assertEquals(type.id, source.storageId)
            assertEquals(source, QuotaIndicatorSource.fromStorageValue(type.id))
        }
        assertEquals(
            QuotaIndicatorSource.LAST_USED,
            QuotaIndicatorSource.fromStorageValue(QuotaIndicatorSource.LAST_USED_ID),
        )
    }
}
