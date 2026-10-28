package lab.dawnforge.hermes.core.push.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import lab.dawnforge.hermes.core.gateway.GatewayEvent

/** How a chat's live events move a turn's notification, with iOS's cases (`WidgetLaunchLinkTests+LiveTurn`). */
class LiveTurnProgressTest {
    private val now = 1_800_000_000L
    private val thinking = LiveTurnProgress.started("a")

    @Test
    fun `tools count and show only their category`() {
        var state = LiveTurnProgress.applying(tool("tool.start", "terminal"), thinking, now)!!
        assertEquals("tool", state.phase)
        assertEquals("terminal", state.tool)
        assertEquals(1, state.tools)
        state = LiveTurnProgress.applying(tool("tool.start", "mcp_github_create_issue"), state, now)!!
        assertEquals("connector", state.tool)
        assertEquals(2, state.tools)
        state = LiveTurnProgress.applying(tool("tool.complete", "mcp_github_create_issue"), state, now)!!
        assertEquals("thinking", state.phase)
        assertNull(state.tool)
        assertEquals(2, state.tools)
    }

    @Test
    fun `clarify waits on the user without counting a tool`() {
        val state = LiveTurnProgress.applying(tool("tool.start", "clarify"), thinking, now)!!
        assertEquals("input", state.phase)
        assertEquals(0, state.tools)
    }

    @Test
    fun `repeated deltas change nothing`() {
        val writing = LiveTurnProgress.applying(event("message.delta"), thinking, now)!!
        assertEquals("writing", writing.phase)
        assertNull(LiveTurnProgress.applying(event("message.delta"), writing, now))
    }

    @Test
    fun `context is a whole bounded percent`() {
        val state = LiveTurnProgress.applying(context(41.6), thinking, now)!!
        assertEquals(42, state.context)
        assertEquals(100, LiveTurnProgress.applying(context(130.0), state, now)!!.context)
        assertEquals(100, LiveTurnProgress.applying(context(1e20), thinking, now)!!.context)
    }

    @Test
    fun `Hermes' usage tick reads as context only with a known window`() {
        val usage = event("session.usage") {
            putJsonObject("usage") {
                put("context_used", 30_000)
                put("context_max", 120_000)
            }
        }
        assertEquals(25, LiveTurnProgress.applying(usage, thinking, now)!!.context)
        val unknown = event("session.usage") { putJsonObject("usage") { put("context_used", 30_000) } }
        assertNull(LiveTurnProgress.applying(unknown, thinking, now))
    }

    @Test
    fun `the turn ends with its outcome and time`() {
        val running = thinking.copy(phase = "tool", tool = "web", tools = 3, context = 20)
        val done = LiveTurnProgress.applying(event("message.complete"), running, now)!!
        assertEquals("done", done.phase)
        assertTrue(done.ended)
        assertNull(done.tool)
        assertEquals(3, done.tools)
        assertEquals(now, done.endedAtSeconds)
        assertEquals("failed", LiveTurnProgress.applying(event("error"), running, now)?.phase)
        assertEquals("stopped", LiveTurnProgress.applying(event("message.interrupted"), running, now)?.phase)
        assertEquals(
            "done",
            LiveTurnProgress.applying(
                event("session.busy") {
                    put("busy", false)
                },
                running,
                now,
            )?.phase,
        )
        assertNull(LiveTurnProgress.applying(event("session.busy") { put("busy", true) }, running, now))
    }

    @Test
    fun `a withdrawn request goes back to thinking only while waiting`() {
        val waiting = thinking.copy(phase = "approval")
        assertEquals("thinking", LiveTurnProgress.applying(event("request.cancel"), waiting, now)?.phase)
        assertNull(
            LiveTurnProgress.applying(event("request.cancel"), thinking.copy(phase = "tool", tool = "files"), now),
        )
        assertNull(
            LiveTurnProgress.applying(event("session.busy") { put("busy", false) }, waiting, now),
            "a pending approval outlasts an idle report",
        )
    }

    @Test
    fun `an open approval or question waits on the user, and none left goes back to thinking`() {
        val approval = LiveTurnProgress.withRequests(thinking, listOf("approval"))!!
        assertEquals("approval", approval.phase)
        assertEquals("input", LiveTurnProgress.withRequests(thinking, listOf("clarify"))?.phase)
        assertEquals("input", LiveTurnProgress.withRequests(thinking, listOf("sudo"))?.phase)
        assertEquals("approval", LiveTurnProgress.withRequests(thinking, listOf("clarify", "approval"))?.phase)
        assertEquals("thinking", LiveTurnProgress.withRequests(approval, emptyList())?.phase)
        assertNull(LiveTurnProgress.withRequests(thinking, emptyList()))
        assertNull(LiveTurnProgress.withRequests(thinking, listOf("desktop.window")), "one this app doesn't show")
    }

    @Test
    fun `held events skip the previous turn's end`() {
        val oldEnd = event("message.complete")
        val tool = tool("tool.start", "terminal")
        assertEquals(2, LiveTurnProgress.newTurnEvents(listOf(oldEnd, event("message.start"), tool)).size)
        assertTrue(
            LiveTurnProgress.newTurnEvents(listOf(tool, oldEnd)).isEmpty(),
            "without a start, it's all the turn before",
        )
    }

    @Test
    fun `tool categories match the plugin's list`() {
        assertEquals("files", LiveTurnProgress.toolCategory("read_file"))
        assertEquals("browser", LiveTurnProgress.toolCategory("browser_click"))
        assertEquals("phone", LiveTurnProgress.toolCategory("talaria_show"))
        assertEquals("skills", LiveTurnProgress.toolCategory("skills_search"))
        assertEquals("web", LiveTurnProgress.toolCategory(" Web_Fetch "))
        assertEquals("tool", LiveTurnProgress.toolCategory("something_new"))
    }

    private fun event(type: String, payload: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) =
        GatewayEvent(type, "s", payload?.let(::buildJsonObject) ?: JsonObject(emptyMap()), 1)

    private fun tool(type: String, name: String) = event(type) { put("name", name) }

    private fun context(percent: Double) = event("context.update") { put("context_percent", percent) }
}
