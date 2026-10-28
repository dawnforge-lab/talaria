package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import lab.dawnforge.hermes.core.model.ChatItem

class ChatReducerTest {
    /**
     * A turn as Hermes 0.22.2 streams it (reasoning, commentary sealed by `message.interim`, a tool
     * round, a final reply whose first delta starts with a blank line), and the rows it stores.
     */
    private val streamed = events(
        """
        {"type":"message.start","session_id":"rt-1","seq":1}
        {"type":"thinking.delta","session_id":"rt-1","payload":{"text":"waiting for the model"},"seq":2}
        {"type":"reasoning.delta","session_id":"rt-1","payload":{"text":"Use "},"seq":3}
        {"type":"reasoning.delta","session_id":"rt-1","payload":{"text":"ls."},"seq":4}
        {"type":"message.delta","session_id":"rt-1","payload":{"text":"Let me "},"seq":5}
        {"type":"message.delta","session_id":"rt-1","payload":{"text":"look."},"seq":6}
        {"type":"message.interim","session_id":"rt-1","payload":{"text":"Let me look.","already_streamed":true},"seq":7}
        {"type":"tool.start","session_id":"rt-1","payload":{"tool_id":"call_a","name":"terminal","context":"ls -la","args":{"command":"ls -la"}},"seq":8}
        {"type":"tool.complete","session_id":"rt-1","payload":{"tool_id":"call_a","name":"terminal","args":{"command":"ls -la"},"duration_s":0.42,"result":{"output":"a.txt\nb.txt","exit_code":0}},"seq":9}
        {"type":"message.delta","session_id":"rt-1","payload":{"text":"\n\nTwo files: "},"seq":10}
        {"type":"message.delta","session_id":"rt-1","payload":{"text":"**a.txt** and b.txt."},"seq":11}
        {"type":"session.usage","session_id":"rt-1","payload":{"usage":{"context_used":2000,"context_max":8000}},"seq":12}
        {"type":"message.complete","session_id":"rt-1","payload":{"text":"Two files: **a.txt** and b.txt.","status":"complete","reasoning":"Use ls."},"seq":13}
        {"type":"session.info","session_id":"rt-1","payload":{"running":false,"title":"Listing files"},"seq":14}
        """,
    )

    private val stored = rows(
        """
        [
          {"id": 2, "role": "assistant", "content": "Let me look.", "reasoning": "Use ls.",
           "tool_calls": [{"id": "call_a", "type": "function", "function": {"name": "terminal", "arguments": "{\"command\": \"ls -la\"}"}}]},
          {"id": 3, "role": "tool", "tool_call_id": "call_a", "tool_name": "terminal", "content": "{\"output\": \"a.txt\\nb.txt\", \"exit_code\": 0}"},
          {"id": 4, "role": "assistant", "content": "Two files: **a.txt** and b.txt."}
        ]
        """,
    )

    @Test
    fun `a streamed turn ends up reading exactly like its stored copy`() {
        val state = streamed.fold()
        assertEquals(normalizeHistory(stored).shown(), state.items.shown())
        assertFalse(state.running)
        assertEquals(1, state.turnsEnded)
        assertEquals("Listing files", state.title)
        assertEquals(25.0, state.context?.percent)
        assertTrue(state.items.none { it is ChatItem.Assistant && it.streaming })
        assertEquals(420L, state.items.filterIsInstance<ChatItem.Tool>().single().durationMs)
    }

    @Test
    fun `every step of the stream shows what has arrived so far`() {
        val midReply = streamed.take(6).fold()
        assertEquals(listOf("reasoning: Use ls.", "assistant: Let me look."), midReply.items.shown())
        assertTrue((midReply.items.last() as ChatItem.Assistant).streaming)
        assertTrue(midReply.running)

        assertEquals("waiting for the model", streamed.take(2).fold().status)

        val toolRunning = streamed.take(8).fold()
        assertEquals("tool terminal [Running] ls -la -> ", toolRunning.items.last().shown())
        assertEquals(null, toolRunning.status)

        val finalStreaming = streamed.take(10).fold().items.last() as ChatItem.Assistant
        assertEquals("Two files: ", finalStreaming.text, "the blank line Hermes puts after a tool round is dropped")
    }

    @Test
    fun `stored history replaces the live rows once the turn has ended`() {
        val ended = streamed.fold()
        val reloaded = ended.withHistory(normalizeHistory(stored))
        assertEquals(emptyList(), reloaded.turn)
        assertEquals(normalizeHistory(stored), reloaded.items)

        // A history load that started before a newer turn ended keeps that turn's rows.
        val stale = ended.withHistory(emptyList(), loadedAtTurnsEnded = 0)
        assertEquals(ended.turn, stale.turn)

        // So does one that lands while the next turn is streaming.
        val next = ended.reduce(event("message.start")).reduce(event("message.delta", """{"text":"Hi"}"""))
        assertEquals("assistant: Hi", next.withHistory(normalizeHistory(stored)).items.last().shown())
    }

    @Test
    fun `a reply already shown is not shown twice`() {
        val state = events(
            """
            {"type":"message.start","session_id":"rt-1"}
            {"type":"message.interim","session_id":"rt-1","payload":{"text":"Done.","already_streamed":false}}
            {"type":"message.complete","session_id":"rt-1","payload":{"text":"Done.","status":"complete","response_previewed":true}}
            """,
        ).fold()
        assertEquals(listOf("assistant: Done."), state.items.shown())
    }

    @Test
    fun `the final text is authoritative for the last segment, and reasoning arrives with it if never streamed`() {
        val state = events(
            """
            {"type":"message.start","session_id":"rt-1"}
            {"type":"message.delta","session_id":"rt-1","payload":{"text":"Draft"}}
            {"type":"message.complete","session_id":"rt-1","payload":{"text":"Final answer","status":"complete","response_transformed":true,"reasoning":"Thought."}}
            """,
        ).fold()
        assertEquals(listOf("reasoning: Thought.", "assistant: Final answer"), state.items.shown())
    }

    @Test
    fun `reasoning sent whole replaces the block`() {
        val state = events(
            """
            {"type":"message.start","session_id":"rt-1"}
            {"type":"reasoning.delta","session_id":"rt-1","payload":{"text":"partial"}}
            {"type":"reasoning.available","session_id":"rt-1","payload":{"text":"The whole thought."}}
            """,
        ).fold()
        assertEquals(listOf("reasoning: The whole thought."), state.items.shown())
    }

    @Test
    fun `a stopped turn keeps what it wrote and says it was stopped`() {
        val state = events(
            """
            {"type":"message.start","session_id":"rt-1"}
            {"type":"message.delta","session_id":"rt-1","payload":{"text":"Half"}}
            {"type":"tool.start","session_id":"rt-1","payload":{"tool_id":"c1","name":"web_search","args":{"query":"x"}}}
            {"type":"message.complete","session_id":"rt-1","payload":{"text":"","status":"interrupted"}}
            {"type":"session.info","session_id":"rt-1","payload":{"running":false}}
            """,
        ).fold()
        assertEquals(
            listOf("assistant: Half", "tool web_search [Stopped] x -> ", "notice Stopped"),
            state.items.shown(),
        )
    }

    @Test
    fun `a failed turn shows Hermes' error, and keeps a partial reply`() {
        val failed = events(
            """
            {"type":"message.start","session_id":"rt-1"}
            {"type":"message.complete","session_id":"rt-1","payload":{"text":"The provider refused the request.","status":"error"}}
            """,
        ).fold()
        assertEquals(listOf("notice Failed The provider refused the request."), failed.items.shown())

        val partial = events(
            """
            {"type":"message.start","session_id":"rt-1"}
            {"type":"message.delta","session_id":"rt-1","payload":{"text":"Partial an"}}
            {"type":"message.complete","session_id":"rt-1","payload":{"text":"Partial an","status":"error","partial":true,"error":"Connection reset"}}
            """,
        ).fold()
        assertEquals(listOf("assistant: Partial an", "notice Failed Connection reset"), partial.items.shown())

        val outsideTurn = ChatState().reduce(event("error", """{"message":"Model switch failed"}"""))
        assertEquals(listOf("notice Failed Model switch failed"), outsideTurn.items.shown())
    }

    @Test
    fun `a replayed tool start does not add a second card`() {
        val start = event("tool.start", """{"tool_id":"c1","name":"terminal","args":{"command":"pwd"}}""")
        val state = ChatState().reduce(event("message.start")).reduce(start).reduce(start)
        assertEquals(1, state.items.count { it is ChatItem.Tool })
    }

    @Test
    fun `a result for a call in the stored history completes that card`() {
        val history = normalizeHistory(
            rows(
                """[{"id": 1, "role": "assistant", "content": "", "tool_calls": [{"id": "c1", "function": {"name": "terminal", "arguments": "{}"}}]}]""",
            ),
            running = true,
        )
        val state = ChatState(history = history, running = true)
            .reduce(event("tool.complete", """{"tool_id":"c1","name":"terminal","result":"done"}"""))
        assertEquals(listOf("tool terminal [Complete]  -> done"), state.items.shown())
    }

    @Test
    fun `a turn already running when the chat opens continues from its text so far`() {
        val state = ChatState().withRunningTurn("\nHello wor").reduce(event("message.delta", """{"text":"ld"}"""))
        assertEquals(listOf("assistant: Hello world"), state.items.shown())
        assertTrue(state.running)
    }
}
