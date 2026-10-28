package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Delivery

class OutgoingTest {
    private fun typed(key: String, text: String, delivery: Delivery = Delivery.Sending) =
        ChatItem.User(key, text, delivery = delivery)

    private val stored = rows(
        """
        [
          {"id": 7, "role": "user", "content": "Hello"},
          {"id": 8, "role": "assistant", "content": "Hi!"}
        ]
        """,
    )

    @Test
    fun `a message shows at once, and its reply streams after it`() {
        val state = ChatState()
            .withOutgoing(typed("out-1", "Hello"))
            .withDelivery("out-1", delivery = null, rowId = "7")
            .reduce(event("message.start"))
            .reduce(event("message.delta", """{"text":"Hi!"}"""))
        assertEquals(listOf("user: Hello", "assistant: Hi!"), state.items.shown())
    }

    @Test
    fun `the stored copy replaces a sent message once the turn ends`() {
        val ended = ChatState()
            .withOutgoing(typed("out-1", "Hello"))
            .withDelivery("out-1", delivery = null, rowId = "7")
            .reduce(event("message.start"))
            .reduce(event("message.complete", """{"text":"Hi!","status":"complete"}"""))
            .reduce(event("session.info", """{"running":false}"""))
        val reloaded = ended.withHistory(normalizeHistory(stored))
        assertEquals(listOf("row-7", "row-8"), reloaded.items.map { it.key })
    }

    @Test
    fun `a sent message found in history while the turn still runs is not shown twice`() {
        val running = ChatState()
            .withOutgoing(typed("out-1", "Hello"))
            .withDelivery("out-1", delivery = null, rowId = "7")
            .reduce(event("message.start"))
            .reduce(event("message.delta", """{"text":"Hi"}"""))
        val reloaded = running.withHistory(normalizeHistory(stored.take(1)))
        assertEquals(listOf("user: Hello", "assistant: Hi"), reloaded.items.shown())
        assertEquals("row-7", reloaded.items.first().key)
    }

    @Test
    fun `a message that failed or is still sending outlives a reload`() {
        val state = ChatState()
            .withOutgoing(typed("out-1", "First"))
            .withDelivery("out-1", Delivery.Failed)
            .withOutgoing(typed("out-2", "Second"))
        val reloaded = state.withHistory(normalizeHistory(stored))
        assertEquals(
            listOf(Delivery.Failed, Delivery.Sending),
            reloaded.items.filterIsInstance<ChatItem.User>().mapNotNull { it.delivery },
        )
        assertEquals(listOf("row-7", "row-8", "out-1", "out-2"), reloaded.items.map { it.key })
    }

    @Test
    fun `a waiting message stays last while the turn streams, and moves up when it is sent`() {
        val state = ChatState()
            .reduce(event("message.start"))
            .withQueued(typed("out-1", "Next question", Delivery.Queued))
            .reduce(event("message.delta", """{"text":"Working on it"}"""))
            .reduce(event("tool.start", """{"tool_id":"c1","name":"terminal","args":{"command":"ls"}}"""))
        assertEquals("user: Next question", state.items.last().shown())

        val sending = state.withOutgoing(state.queued.single().copy(delivery = Delivery.Sending))
        assertTrue(sending.queued.isEmpty())
        assertEquals(Delivery.Sending, (sending.items.last() as ChatItem.User).delivery)

        assertEquals(emptyList(), state.withoutQueued("out-1").queued)
    }

    @Test
    fun `a message put into the running turn reads as a steer`() {
        val state = ChatState()
            .reduce(event("message.start"))
            .withOutgoing(typed("out-1", "Shorter please"))
            .withDelivery("out-1", delivery = null, isSteer = true)
        val message = state.items.filterIsInstance<ChatItem.User>().single()
        assertTrue(message.isSteer)
        assertEquals(null, message.delivery)
    }

    @Test
    fun `a failed message taken back leaves the transcript`() {
        val state = ChatState().withOutgoing(typed("out-1", "Oops")).withDelivery("out-1", Delivery.Failed)
        assertEquals(emptyList(), state.withoutOutgoing("out-1").items)
    }
}
