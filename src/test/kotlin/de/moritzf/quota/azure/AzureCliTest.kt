package de.moritzf.quota.azure

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzureCliTest {
    @Test
    fun resolvesDefaultAndPinnedSubscriptionsUsingOnlyAccountList() {
        val calls = mutableListOf<List<String>>()
        val cli = AzureCli(Path.of("/test/az"), run = { _, args, _, _ ->
            calls += args
            """[
                {"id":"11111111-0000-0000-0000-000000000001","name":"First","isDefault":false},
                {"id":"22222222-0000-0000-0000-000000000001","name":"Second","isDefault":true}
            ]"""
        })
        assertEquals("Second", cli.resolveAccount(null).subscriptionName)
        assertEquals("First", cli.resolveAccount("11111111-0000-0000-0000-000000000001").subscriptionName)
        assertTrue(calls.all { it == listOf("account", "list", "--output", "json") })
    }

    @Test
    fun cliDefaultAndLoginChangesAreVisibleBeforeThePreviousTokenExpires() {
        for (subscription in listOf(null, "00000000-0000-0000-0000-000000000001")) {
            var token = "first-identity"
            val cli = AzureCli(Path.of("/test/az"), run = { _, _, _, _ ->
                """{"accessToken":"$token","expires_on":4102444800}"""
            })
            assertEquals("first-identity", cli.accessToken(AZURE_COGNITIVE_SCOPE, subscription).accessToken)
            token = "second-identity"
            assertEquals("second-identity", cli.accessToken(AZURE_COGNITIVE_SCOPE, subscription).accessToken)
        }
    }
}
