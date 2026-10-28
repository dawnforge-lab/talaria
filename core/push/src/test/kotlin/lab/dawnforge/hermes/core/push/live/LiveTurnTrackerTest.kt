package lab.dawnforge.hermes.core.push.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.gateway.ServerRequest

class LiveTurnTrackerTest {
    private var nowMs = 1_800_000_000_000L
    private val tracker = LiveTurnTracker { nowMs }

    @Test
    fun `a turn that waits for an approval shows it the moment the app is left`() {
        // On the emulator (2026-10-26): the turn reached its approval before the relay knew it,
        // so the relay had nothing to push and the notification never showed.
        tracker.started("a", listOf("runtime", "stored"))
        tracker.observe(event("message.start"))
        tracker.observe(tool("terminal"))
        tracker.requests(listOf(request("approval", "runtime")))
        assertEquals(listOf("approval"), tracker.current().map { it.phase })
        assertEquals(1, tracker.current().single().tools)
    }

    @Test
    fun `answering the approval goes back to thinking, and the end stops following it`() {
        tracker.started("a", listOf("runtime"))
        tracker.requests(listOf(request("approval", "runtime")))
        assertEquals("thinking", tracker.requests(emptyList()).single().phase)
        nowMs += 10_000
        val end = tracker.observe(event("message.complete")).single()
        assertTrue(end.ended)
        assertTrue(tracker.current().isEmpty())
    }

    @Test
    fun `another chat's events and requests change nothing`() {
        tracker.started("a", listOf("runtime"))
        assertTrue(tracker.observe(tool("terminal", session = "other")).isEmpty())
        assertTrue(tracker.requests(listOf(request("approval", "other"))).isEmpty())
        assertEquals("thinking", tracker.current().single().phase)
    }

    @Test
    fun `what streamed while the message was being sent belongs to its turn`() {
        tracker.submitting(listOf("runtime"))
        tracker.observe(event("message.complete"))
        tracker.observe(event("message.start"))
        tracker.observe(tool("web_search"))
        val state = tracker.started("a", listOf("runtime"))
        assertEquals("tool", state.phase)
        assertEquals("web", state.tool)
    }

    @Test
    fun `an idle report right after the start is not taken as the end`() {
        tracker.started("a", listOf("runtime"))
        assertTrue(tracker.observe(event("session.busy") { put("busy", false) }).isEmpty())
        nowMs += 6_000
        assertTrue(tracker.observe(event("session.busy") { put("busy", false) }).single().ended)
    }

    @Test
    fun `a new turn in the same chat replaces the one before`() {
        tracker.started("a", listOf("runtime"))
        tracker.started("b", listOf("runtime", "stored"))
        assertEquals(listOf("b"), tracker.current().map { it.activity })
    }

    @Test
    fun `the relay's push is where the turn stands`() {
        tracker.started("a", listOf("runtime"))
        tracker.pushed(LiveTurnUpdate("a", false, "input", null, 2, 30, null))
        assertEquals("input", tracker.current().single().phase)
        assertEquals(2, tracker.current().single().tools)
    }

    private fun event(
        type: String,
        session: String = "runtime",
        payload: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null,
    ) = GatewayEvent(type, session, payload?.let(::buildJsonObject) ?: JsonObject(emptyMap()), 1)

    private fun tool(name: String, session: String = "runtime") = event("tool.start", session) { put("name", name) }

    private fun request(method: String, session: String) =
        ServerRequest("srq-$method-$session", method, buildJsonObject { put("session_id", session) })
}
