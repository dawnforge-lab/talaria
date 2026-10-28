package lab.dawnforge.hermes.core.push.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The relay's live turn data messages (hermes-push-relay `liveActivityFcm`). */
class LiveTurnUpdateTest {
    @Test
    fun `an update and an end read as the relay sends them`() {
        val update = LiveTurnUpdate.parse(
            mapOf(
                "category" to "HERMES_LIVE_TURN",
                "activity" to "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                "event" to "update",
                "phase" to "tool",
                "tools" to "3",
                "tool" to "web",
                "context" to "40",
                "stale_at" to "1000",
            ),
        )!!
        assertEquals(LiveTurnUpdate("3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d", false, "tool", "web", 3, 40, null), update)
        val ended = LiveTurnUpdate.parse(
            mapOf(
                "category" to "HERMES_LIVE_TURN",
                "activity" to "a",
                "event" to "end",
                "phase" to "done",
                "tools" to "3",
                "ended_at" to "1200",
            ),
        )!!
        assertTrue(ended.ended)
        assertEquals(1200L, ended.endedAtSeconds)
    }

    @Test
    fun `anything else is not a live turn`() {
        assertNull(LiveTurnUpdate.parse(mapOf("category" to "OTHER", "activity" to "a", "phase" to "tool")))
        assertNull(LiveTurnUpdate.parse(mapOf("category" to "HERMES_LIVE_TURN", "phase" to "tool")))
        assertNull(
            LiveTurnUpdate.parse(mapOf("category" to "HERMES_LIVE_TURN", "activity" to "a", "phase" to "dancing")),
        )
    }
}
