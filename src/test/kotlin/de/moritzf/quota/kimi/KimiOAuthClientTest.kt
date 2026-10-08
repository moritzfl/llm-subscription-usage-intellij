package de.moritzf.quota.kimi

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class KimiOAuthClientTest {
    @Test
    fun deviceAuthorizationAndPollingUseInjectedIdentity() {
        val ids = LinkedBlockingQueue<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            ids += exchange.requestHeaders.getFirst("X-Msh-Device-Id")
            val body =
                if (exchange.requestURI.path.endsWith("device_authorization")) {
                        """{"device_code":"device-code","user_code":"user-code","verification_uri":"https://example.com","expires_in":600}"""
                    } else {
                        """{"access_token":"token","refresh_token":"refresh","expires_in":3600}"""
                    }
                    .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client =
                KimiOAuthClient(
                    oauthHost = "http://127.0.0.1:${server.address.port}",
                    deviceId = "login-device",
                )
            val authorization = client.requestDeviceAuthorization()
            assertIs<KimiDeviceTokenPollResult.Authorized>(
                client.pollDeviceToken(authorization.deviceCode)
            )
            assertEquals(listOf("login-device", "login-device"), ids.toList())
        } finally {
            server.stop(0)
        }
    }
}
