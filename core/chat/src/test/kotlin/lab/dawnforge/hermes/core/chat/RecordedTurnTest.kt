package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.ToolStatus

/**
 * Turns recorded from a real Hermes 0.22.2 (core:data's LiveTurnCaptureTest, with the session
 * details Hermes sends trimmed to what the app reads): every event of the turn, and the rows Hermes
 * stored for it. Streamed or loaded, the turn must read the same.
 */
class RecordedTurnTest {
    private fun replay(name: String): Pair<ChatState, List<ChatItem>> {
        val events = events(resource("$name.events.jsonl"))
        val history = normalizeHistory(rows(resource("$name.history.json")))
        val storedTurn = history.drop(history.indexOfLast { it is ChatItem.User } + 1)
        return events.fold() to storedTurn
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    @Test
    fun `one tool call, then a reply whose reasoning Hermes repeats as its text`() {
        val (streamed, stored) = replay("tool-turn")
        assertEquals(stored.shown(), streamed.items.shown())
        assertEquals(
            listOf("reasoning", "tool", "assistant"),
            streamed.items.map { it.shown().substringBefore(' ').removeSuffix(":") },
        )
        assertEquals(2620L, (streamed.items[1] as ChatItem.Tool).durationMs)
        assertFalse(streamed.running)
        assertEquals("Run echo hermes-android-check test", streamed.title)
    }

    @Test
    fun `commentary sealed by an interim, two tool rounds and a Markdown reply`() {
        val (streamed, stored) = replay("commentary-turn")
        assertEquals(stored.shown(), streamed.items.shown())
        val tools = streamed.items.filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf("echo alpha", "echo beta"), tools.map { it.summary })
        assertTrue(tools.all { it.status == ToolStatus.Complete })
        assertEquals("- `alpha`\n- `beta`", (streamed.items.last() as ChatItem.Assistant).text)
    }

    @Test
    fun `a replay of the whole turn after a reconnect changes nothing`() {
        val events = events(resource("commentary-turn.events.jsonl"))
        val once = events.fold()
        // The gateway drops events it has seen by seq; the tool cards must hold even if a start comes twice.
        val starts = events.filter { it.type == "tool.start" }
        assertEquals(once.items.shown(), starts.fold(once) { state, e -> state.reduce(e) }.items.shown())
    }

    @Test
    fun `the stored copy replaces the live rows once the turn ends`() {
        val (streamed, stored) = replay("tool-turn")
        assertEquals(1, streamed.turnsEnded)
        val reloaded = streamed.withHistory(stored)
        assertTrue(reloaded.turn.isEmpty())
        assertEquals(stored, reloaded.items)
    }
}
