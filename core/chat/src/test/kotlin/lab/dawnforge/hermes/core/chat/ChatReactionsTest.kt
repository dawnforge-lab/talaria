package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import lab.dawnforge.hermes.core.gateway.GatewayEvent
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Reaction

class ChatReactionsTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `picking a reaction replaces the user's, and picking it again takes it back`() {
        val agent = Reaction("❤️", byAgent = true)
        assertEquals(listOf(agent, Reaction("👍", false)), listOf(agent).picking("👍"))
        assertEquals(listOf(agent, Reaction("😂", false)), listOf(agent, Reaction("👍", false)).picking("😂"))
        assertEquals(listOf(agent), listOf(agent, Reaction("👍", false)).picking("👍"))
    }

    @Test
    fun `a reply is sent with the quote above it, and read back apart`() {
        val sent = replyEnvelope("First line\n\n  indented line  ", "Thanks!")
        assertEquals(
            "[Replying to your earlier message]\n> First line\n>\n>   indented line\n\nThanks!",
            sent,
        )
        val read = ReplyText.parse(sent)
        assertEquals("First line\n\n  indented line", read.quoted)
        assertEquals("Thanks!", read.text)
        assertEquals(ReplyText(null, "Just a message"), ReplyText.parse("Just a message"))
    }

    @Test
    fun `a branch keeps the user and assistant messages with text, up to its reply`() {
        val rows = listOf(
            json("""{"id":1,"role":"system","content":"prompt"}"""),
            json("""{"id":2,"role":"user","content":"hi"}"""),
            json("""{"id":3,"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"x"}}]}"""),
            json("""{"id":4,"role":"tool","content":"out","tool_call_id":"c1"}"""),
            json("""{"id":5,"role":"assistant","content":"hello"}"""),
            json("""{"id":6,"role":"user","content":"scaffold","display_kind":"hidden"}"""),
            json("""{"id":7,"role":"assistant","content":"more"}"""),
        )
        assertEquals(2, branchCount(rows, 5))
        assertEquals(4, branchCount(rows, 7))
        assertNull(branchCount(rows, 4))
        assertNull(branchCount(rows, 99))
    }

    @Test
    fun `stored rows carry their id and reactions`() {
        val items = normalizeHistory(
            listOf(
                json(
                    """{"id":10,"role":"user","content":"hi","display_metadata":{"reactions":[{"emoji":"👀","author":"agent"}]}}""",
                ),
                json(
                    """{"id":11,"role":"assistant","content":"hello",
                        "display_metadata":"{\"reactions\":[{\"emoji\":\"👍\",\"author\":\"user\",\"at\":1.0}]}"}""",
                ),
            ),
        )
        val user = items[0] as ChatItem.User
        val reply = items[1] as ChatItem.Assistant
        assertEquals(10L, user.rowId)
        assertEquals(listOf(Reaction("👀", true)), user.reactions)
        assertEquals(11L, reply.rowId)
        assertEquals(listOf(Reaction("👍", false)), reply.reactions)
    }

    @Test
    fun `the agent's reaction lands on its row, or on the newest unstored message of its role`() {
        fun ChatState.reduce(type: String, payload: JsonObject) = reduce(GatewayEvent(type, "s1", payload, seq = null))
        var state = ChatState().withHistory(listOf(ChatItem.User("row-10", "hi", rowId = 10)))
        state =
            state.reduce(
                "message.reaction",
                json("""{"row_id":10,"role":"user","reactions":[{"emoji":"👍","author":"agent"}]}"""),
            )
        assertEquals(listOf(Reaction("👍", true)), (state.items[0] as ChatItem.User).reactions)
        state = state.withOutgoing(ChatItem.User("out-1", "next"))
        state =
            state.reduce(
                "message.reaction",
                json("""{"row_id":12,"role":"user","reactions":[{"emoji":"🎉","author":"agent"}]}"""),
            )
        val sent = state.items.last() as ChatItem.User
        assertEquals(12L, sent.rowId)
        assertEquals(listOf(Reaction("🎉", true)), sent.reactions)
    }
}
