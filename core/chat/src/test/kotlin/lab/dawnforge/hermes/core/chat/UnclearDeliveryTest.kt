package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import lab.dawnforge.hermes.core.model.ChatItem

class UnclearDeliveryTest {
    private val earlier = ChatItem.User("row-7", "Hello")

    private fun settle(
        stored: List<ChatItem.User> = listOf(earlier),
        inflight: String? = null,
        queued: String? = null,
        text: String = "Hello",
    ) = UnclearDelivery.settle(text, knownUsers = setOf(earlier.key), stored, inflight, queued)

    @Test
    fun `a newer stored message with its words is the one sent`() {
        val stored = ChatItem.User("row-9", "Hello", isSteer = true)
        assertEquals(UnclearDelivery.Stored(stored), settle(stored = listOf(earlier, stored)))
    }

    @Test
    fun `an earlier message with the same words is not taken for it`() {
        assertEquals(UnclearDelivery.Lost, settle())
    }

    @Test
    fun `the running turn answering it means Hermes has it`() {
        // Hermes' copy carries the attachments' references ahead of the typed words.
        assertEquals(UnclearDelivery.Received, settle(inflight = "@file:notes.txt\n\nHello"))
    }

    @Test
    fun `a message waiting for the running turn means Hermes has it`() {
        assertEquals(UnclearDelivery.Received, settle(inflight = "Something else", queued = "Hello"))
    }

    @Test
    fun `no sign of it on Hermes means it goes again`() {
        assertEquals(UnclearDelivery.Lost, settle(inflight = "Something else"))
    }
}
