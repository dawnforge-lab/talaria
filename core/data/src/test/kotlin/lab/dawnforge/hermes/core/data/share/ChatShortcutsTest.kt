package lab.dawnforge.hermes.core.data.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatShortcutsTest {
    @Test
    fun `a Direct Share id names the chat it was published for`() {
        val id = ChatShortcuts.id("dash-1", "work", "20261021_1_abc")
        assertEquals(ChatShortcuts.Chat("dash-1", "work", "20261021_1_abc"), ChatShortcuts.parse(id))
        listOf("", "chat|a|b", "other|a|b|c", "chat|a||c", "chat|a|b|c|d").forEach {
            assertNull(ChatShortcuts.parse(it), it)
        }
    }
}
