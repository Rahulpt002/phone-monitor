package com.hotspotmonitor.agent.sync

import com.hotspotmonitor.agent.protocol.AgentJson
import com.hotspotmonitor.agent.protocol.DeviceInfo
import com.hotspotmonitor.agent.protocol.PairResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class PairingApiTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
    private val device = DeviceInfo(
        deviceId = null,
        manufacturer = "Google",
        model = "Pixel 6",
        device = "oriole",
        androidVersion = "14",
        sdk = 34,
        agentVersion = "0.1.0",
        profileId = "google_pixel",
        rooted = true,
    )

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun base(): String = server.url("/").toString().trimEnd('/')

    private fun failure(block: suspend () -> PairResponse): Throwable =
        runBlocking { runCatching { block() } }.exceptionOrNull() ?: error("expected a failure")

    @Test
    fun pairsAndSendsTheNormalizedCodeAndDevice() = runBlocking {
        val ws = "ws://localhost:${server.port}/ws/agent"
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"device_id":"d_3f9c2a","device_token":"tok_secret","ws_url":"$ws","server_url":"${base()}/"}""",
            ),
        )
        val response = PairingApi(client, allowHttp = true).pair(base() + "/", " abcd-2345 ", device)
        assertEquals("d_3f9c2a", response.deviceId)
        assertEquals("tok_secret", response.deviceToken)
        assertEquals(ws, response.wsUrl)
        assertEquals(PairingUrls.normalizeBaseUrl(base(), allowHttp = true), response.serverUrl)
        assertFalse("the token never appears in toString", response.toString().contains("tok_secret"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/pair", request.path)
        val body = AgentJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("ABCD2345", body.getValue("code").jsonPrimitive.content)
        assertEquals("Pixel 6", body.getValue("device").jsonObject.getValue("model").jsonPrimitive.content)
        assertFalse("device_id is assigned by the Server", body.getValue("device").jsonObject.containsKey("device_id"))
    }

    @Test
    fun aMissingOrInvalidWsUrlIsDerivedFromTheServerUrl() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"device_id":"d_1","device_token":"t","ws_url":"ftp://nope"}"""))
        val response = PairingApi(client, allowHttp = true).pair(base(), "ABCD2345", device)
        assertEquals(PairingUrls.agentWsUrl(response.serverUrl), response.wsUrl)
        assertTrue(response.wsUrl.startsWith("ws://"))
        assertTrue(response.wsUrl.endsWith("/ws/agent"))
    }

    @Test
    fun aServerErrorCarriesStatusCodeAndMessage() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":"unauthorized","message":"Pairing code is wrong, expired or already used"}"""),
        )
        val e = failure { PairingApi(client, allowHttp = true).pair(base(), "ABCD2345", device) }
        assertTrue(e is PairingException)
        e as PairingException
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
        assertTrue(e.message!!.contains("Pairing code is wrong"))
    }

    @Test
    fun anAnswerThatIsNotAPairResponseIsRejected() {
        server.enqueue(MockResponse().setBody("<html>Render is waking up</html>"))
        val e = failure { PairingApi(client, allowHttp = true).pair(base(), "ABCD2345", device) }
        assertTrue(e is PairingException)
        assertTrue(e.message!!.contains("Unexpected answer"))
    }

    @Test
    fun plainHttpIsRefusedOutsideDebugBuildsWithoutAnyRequest() {
        val e = failure { PairingApi(client, allowHttp = false).pair(base(), "ABCD2345", device) }
        assertTrue(e is PairingException)
        assertTrue(e.message!!.contains("https://"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anEmptyCodeIsRefusedWithoutAnyRequest() {
        val e = failure { PairingApi(client, allowHttp = true).pair(base(), " - ", device) }
        assertTrue(e is PairingException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anUnreachableServerIsReported() {
        val gone = MockWebServer()
        gone.start()
        val url = gone.url("/").toString().trimEnd('/')
        gone.shutdown()
        val e = failure { PairingApi(client, allowHttp = true).pair(url, "ABCD2345", device) }
        assertTrue(e is PairingException)
        assertTrue(e.message!!.startsWith("Cannot reach"))
    }
}
