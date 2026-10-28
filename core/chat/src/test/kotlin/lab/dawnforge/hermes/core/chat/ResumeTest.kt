package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.ToolStatus

/**
 * Coming back to a chat whose turn is running: after a reconnect, or opening it from the list while
 * another device's turn streams. Built on the recorded commentary turn (two tool rounds).
 */
class ResumeTest {
    private val events = events(resource("commentary-turn.events.jsonl"))
    private val rows = rows(resource("commentary-turn.history.json"))

    private fun resource(name: String) = checkNotNull(javaClass.getResource("/fixtures/$name")).readText()

    /** The events up to and including [seq]. */
    private fun upTo(seq: Long) = events.filter { (it.seq ?: 0) <= seq }

    private fun after(seq: Long) = events.filter { (it.seq ?: 0) > seq }

    /** The stored rows up to row [id], as a history loaded mid-turn would have them. */
    private fun storedUpTo(id: Int) = rows.filter { (it["id"].toString().toInt()) <= id }

    @Test
    fun `a history loaded mid-turn shows nothing twice, and the rest of the turn still lands`() {
        // Seq 56 is the first tool's result; rows 54 to 56 (the prompt, the commentary with its call, the
        // result) are stored by then.
        val midTurn = upTo(56).fold()
        val reloaded = midTurn.withHistory(normalizeHistory(storedUpTo(56), running = true))
        val shown = reloaded.items.shown()
        assertEquals(shown.distinct(), shown, "no row twice: $shown")
        assertEquals(1, reloaded.items.count { it is ChatItem.Tool })
        assertEquals(ToolStatus.Complete, reloaded.items.filterIsInstance<ChatItem.Tool>().single().status)

        val finished = after(56).fold(reloaded).withHistory(normalizeHistory(rows))
        assertEquals(normalizeHistory(rows).shown(), finished.items.shown())
    }

    @Test
    fun `the live result of a call is kept when its stored copy has none yet`() {
        // The call is stored (row 55) before its result row (56): a reload in between keeps the live result.
        val midTurn = upTo(56).fold()
        val reloaded = midTurn.withHistory(normalizeHistory(storedUpTo(55), running = true))
        val tool = reloaded.items.filterIsInstance<ChatItem.Tool>().single()
        assertEquals(ToolStatus.Complete, tool.status)
        assertEquals("alpha", tool.output)
    }

    @Test
    fun `a chat opened mid-turn continues the segment being written, not the ones already stored`() {
        val history = normalizeHistory(storedUpTo(56), running = true)
        val sealed = (history.filterIsInstance<ChatItem.Assistant>().single()).text
        val opened = ChatState(history = history).withRunningTurn("$sealed\n\n- `alpha`\n- `be")
        assertEquals("assistant: - `alpha`\n- `be", opened.items.last().shown())
        assertTrue(opened.running)

        val continued = opened.reduce(event("message.delta", """{"text":"ta`"}"""))
        assertEquals("assistant: - `alpha`\n- `beta`", continued.items.last().shown())
    }

    @Test
    fun `a chat opened between segments starts with nothing streaming`() {
        val history = normalizeHistory(storedUpTo(56), running = true)
        val sealed = (history.filterIsInstance<ChatItem.Assistant>().single()).text
        val opened = ChatState(history = history).withRunningTurn(sealed)
        assertEquals(history, opened.items)
        assertTrue(opened.running)
    }

    @Test
    fun `a snapshot taken after the turn ended settles it`() {
        val midTurn = upTo(56).fold()
        val settled = midTurn.withRunning(false)
        assertFalse(settled.running)
        assertEquals(midTurn.turnsEnded + 1, settled.turnsEnded, "asks for the stored copy")
        assertTrue(settled.items.none { it is ChatItem.Assistant && it.streaming })
        assertEquals(settled, settled.withRunning(false))
    }
}
