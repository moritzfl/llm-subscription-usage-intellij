package de.moritzf.quota.opencode

import de.moritzf.quota.idea.auth.OAuthCredentials
import de.moritzf.quota.shared.JsonSupport
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenCodeOAuthClientTest {
    @Test
    fun nativeDeviceFlowUsesJsonAndResolvesRelativeVerificationUrl() {
        OpenCodeTestServer { request ->
            if (request.path.endsWith("/code")) 200 to """{"device_code":"device","user_code":"ABCD","verification_uri_complete":"/console/device?user_code=ABCD","expires_in":600,"interval":5}"""
            else 200 to TOKEN
        }.use { server ->
            val client = OpenCodeOAuthClient(endpoint = server.endpoint)
            val authorization = client.requestDeviceAuthorization()
            assertEquals(server.endpoint.resolve("device?user_code=ABCD").toString(), client.verificationUrl(authorization))
            val result = client.pollDeviceToken(authorization.deviceCode) as OpenCodeDeviceTokenResult.Authorized
            assertEquals("access", result.credentials.accessToken)
            assertEquals("refresh", result.credentials.refreshToken)
            assertEquals("org_selected", result.credentials.accountId)
            assertTrue(result.credentials.expiresAt > System.currentTimeMillis())
            val device = JsonSupport.json.parseToJsonElement(server.requests[0].body).jsonObject
            assertEquals("opencode-cli", device["client_id"]?.jsonPrimitive?.content)
            assertEquals("true", device["supports_org_scope"]?.jsonPrimitive?.content)
            val token = JsonSupport.json.parseToJsonElement(server.requests[1].body).jsonObject
            assertEquals("urn:ietf:params:oauth:grant-type:device_code", token["grant_type"]?.jsonPrimitive?.content)
            assertEquals("device", token["device_code"]?.jsonPrimitive?.content)
            assertEquals("opencode-cli", token["client_id"]?.jsonPrimitive?.content)
            server.requests.forEach {
                assertEquals("POST", it.method)
                assertEquals("application/json", it.headers.getFirst("Content-Type"))
                assertNull(it.headers.getFirst("Cookie"))
            }
        }
    }

    @Test
    fun recognizesPendingAndSlowDownWithoutTreatingDenialAsPending() {
        for (status in listOf(200, 400)) {
            for ((error, expected) in listOf("authorization_pending" to OpenCodeDeviceTokenResult.Pending, "slow_down" to OpenCodeDeviceTokenResult.SlowDown)) {
                OpenCodeTestServer { status to """{"error":"$error"}""" }.use { server ->
                    assertEquals(expected, OpenCodeOAuthClient(endpoint = server.endpoint).pollDeviceToken("device"))
                }
            }
        }
        OpenCodeTestServer { 400 to """{"error":"access_denied","detail":"secret"}""" }.use { server ->
            val failure = assertFailsWith<OpenCodeQuotaException> {
                OpenCodeOAuthClient(endpoint = server.endpoint).pollDeviceToken("device")
            }
            assertEquals("OpenCode login was denied.", failure.message)
            assertNull(failure.rawBody)
            assertNull(failure.cause)
        }
    }

    @Test
    fun refreshRotatesBothTokensAndPreservesScopeWhenResponseOmitsIt() {
        OpenCodeTestServer { 200 to TOKEN.replace(",\"org_id\":\"org_selected\"", "") }.use { server ->
            val result = OpenCodeOAuthClient(endpoint = server.endpoint).refreshCredentials(
                OAuthCredentials("old-access", "old-refresh", 0, "org_existing"),
            )
            assertEquals("access", result.accessToken)
            assertEquals("refresh", result.refreshToken)
            assertEquals("org_existing", result.accountId)
            val request = JsonSupport.json.parseToJsonElement(server.requests.single().body).jsonObject
            assertEquals("refresh_token", request["grant_type"]?.jsonPrimitive?.content)
            assertEquals("old-refresh", request["refresh_token"]?.jsonPrimitive?.content)
            assertNull(request["device_code"])
        }
    }

    @Test
    fun invalidTokenResponseNeverLeaksTokenBodyThroughException() {
        OpenCodeTestServer { 200 to """{"access_token":"secret"}""" }.use { server ->
            val failure = assertFailsWith<OpenCodeQuotaException> {
                OpenCodeOAuthClient(endpoint = server.endpoint).pollDeviceToken("device")
            }
            assertNull(failure.rawBody)
            assertNull(failure.cause)
            assertTrue(!failure.toString().contains("secret"))
        }
    }

    companion object {
        const val TOKEN = """{"access_token":"access","refresh_token":"refresh","expires_in":600,"org_id":"org_selected"}"""
    }
}
