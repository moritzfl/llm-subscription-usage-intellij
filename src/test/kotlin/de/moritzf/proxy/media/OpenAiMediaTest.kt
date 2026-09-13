package de.moritzf.proxy.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenAiMediaTest {
    @Test
    fun advertisesWorkingMediaIdsPerProvider() {
        assertEquals(
            setOf("sg-grok-imagine-image"),
            OpenAiMedia.advertisedMediaIds(setOf("supergrok")),
        )
        assertTrue("mi-voxtral-mini-tts-2603" in OpenAiMedia.advertisedMediaIds(setOf("mistral")))
        assertTrue(OpenAiMedia.isChatDiscoveryId("mistral-small-latest"))
        assertTrue(OpenAiMedia.isChatDiscoveryId("codestral-latest"))
        assertTrue(!OpenAiMedia.isChatDiscoveryId("voxtral-mini-tts-2603"))
        assertTrue(!OpenAiMedia.isChatDiscoveryId("grok-imagine-image"))
    }

    @Test
    fun mapsPrefixedModels() {
        assertEquals("supergrok", OpenAiMedia.providerIdForModel("sg-grok-imagine-image"))
        assertEquals("grok-imagine-image", OpenAiMedia.upstreamModel("sg-grok-imagine-image"))
        assertEquals("minimax", OpenAiMedia.providerIdForModel("mm-image-01"))
        assertNull(OpenAiMedia.providerIdForModel("gpt-4o"))
    }

    @Test
    fun rejectsB64() {
        assertEquals("response_format=b64_json is not supported. Use url.", OpenAiMedia.rejectB64("b64_json"))
        assertNull(OpenAiMedia.rejectB64("url"))
    }

    @Test
    fun wrapsImageUrl() {
        val json = OpenAiMedia.imageUrlResponse("https://example.test/a.png", created = 1)
        assertEquals("https://example.test/a.png", json["data"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun readsUrlFromProviderJson() {
        val body = """{"data":[{"url":"https://imgen.x.ai/out.png"}]}"""
        assertEquals("https://imgen.x.ai/out.png", OpenAiMedia.imageUrlFromProviderJson(body))
    }
}
