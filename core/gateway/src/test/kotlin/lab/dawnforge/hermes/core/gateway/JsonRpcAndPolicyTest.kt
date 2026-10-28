package lab.dawnforge.hermes.core.gateway

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class JsonRpcAndPolicyTest {
    @Test
    fun `frames are classified`() {
        val event = assertIs<Inbound.Event>(
            Inbound.parse(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"ab12cd34","payload":{"text":"hi"},"seq":7}}""",
            ),
        ).event
        assertEquals(GatewayEvent("message.delta", "ab12cd34", event.payload, 7), event)
        assertEquals(JsonPrimitive("hi"), event.payload!!["text"])

        val broadcast = assertIs<Inbound.Event>(
            Inbound.parse(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"sessions.changed","session_id":""}}""",
            ),
        ).event
        assertNull(broadcast.sessionId)
        assertNull(broadcast.seq)

        val request = assertIs<Inbound.Request>(
            Inbound.parse(
                """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"s1","command":"ls"}}""",
            ),
        ).request
        assertEquals("s1", request.sessionId)

        val result = assertIs<Inbound.Response>(Inbound.parse("""{"jsonrpc":"2.0","id":"c1","result":{"ok":true}}"""))
        assertNull(result.error)
        val error = assertIs<Inbound.Response>(
            Inbound.parse(
                """{"jsonrpc":"2.0","id":"c2","error":{"code":4090,"message":"busy","data":{"reason":"SESSION_NOT_OWNED"}}}""",
            ),
        ).error!!
        assertEquals(4090, error.code)
        assertEquals("SESSION_NOT_OWNED", error.reason)

        assertEquals(Inbound.Ignored, Inbound.parse("not json"))
        assertEquals(Inbound.Ignored, Inbound.parse("""[1,2]"""))
    }

    @Test
    fun `backoff doubles up to the cap and stays in the upper half in the foreground`() {
        val policy = GatewayPolicy()
        val random = Random(1)
        repeat(200) {
            val first = policy.retryDelayMs(1, foreground = true, random = random)
            assertTrue(first in 500..1_000, "first retry $first")
            val third = policy.retryDelayMs(3, foreground = true, random = random)
            assertTrue(third in 2_000..4_000, "third retry $third")
            val late = policy.retryDelayMs(20, foreground = true, random = random)
            assertTrue(late in 4_000..8_000, "capped retry $late")
            val background = policy.retryDelayMs(20, foreground = false, random = random)
            assertTrue(background in 500..60_000, "background retry $background")
        }
    }
}
