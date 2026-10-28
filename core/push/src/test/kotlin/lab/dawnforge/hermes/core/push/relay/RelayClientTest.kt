package lab.dawnforge.hermes.core.push.relay

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class RelayClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = RelayClient(OkHttpClient())
    private val relay = server.url("/")

    @AfterTest
    fun stop() = server.close()

    @Test
    fun `only a bare https address is a relay`() {
        assertEquals(
            "https://home.example.ts.net:8443/",
            RelayClient.relayUrl(" https://home.example.ts.net:8443 ").toString(),
        )
        assertEquals("https://push.example/relay/", RelayClient.relayUrl("https://push.example/relay").toString())
        assertNull(RelayClient.relayUrl("http://push.example"))
        assertNull(RelayClient.relayUrl("https://user:pw@push.example"))
        assertNull(RelayClient.relayUrl("https://push.example/?q=1"))
        assertNull(RelayClient.relayUrl("not a url"))
    }

    @Test
    fun `the relay's refusals read as what they mean`() = runBlocking {
        val cases = listOf(
            """{"error":"platform_unavailable"}""" to RelayException.NoAndroidSupport::class,
            """{"error":"invalid_topic"}""" to RelayException.AppNotAllowed::class,
            """{"error":"invalid_device_token"}""" to RelayException.TokenRefused::class,
            """{"error":"token_proof_invalid"}""" to RelayException.ProofRefused::class,
            """{"error":"push_rejected","reason":"UNAVAILABLE"}""" to RelayException.Failed::class,
        )
        for ((body, expected) in cases) {
            val code = if (expected == RelayException.Failed::class) 502 else 400
            server.enqueue(MockResponse.Builder().code(code).body(body).build())
            val error =
                assertFailsWith<RelayException> {
                    client.requestChallenge(relay, "t".repeat(40), "lab.dawnforge.hermes")
                }
            assertEquals(expected, error::class, body)
        }
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        assertFailsWith<RelayException.Unauthorized> {
            client.createPairing(relay, RelayInstallation("inst", "inst.secret"), "dash")
        }
        server.enqueue(MockResponse.Builder().code(429).body("""{"error":"rate_limited"}""").build())
        assertFailsWith<RelayException.RateLimited> {
            client.createPairing(relay, RelayInstallation("inst", "inst.secret"), "dash")
        }
        Unit
    }

    @Test
    fun `an unreachable relay says so`() = runBlocking {
        val gone = server.url("/")
        server.close()
        assertFailsWith<RelayException.Unreachable> {
            client.requestChallenge(gone, "t".repeat(40), "lab.dawnforge.hermes")
        }
        Unit
    }
}
