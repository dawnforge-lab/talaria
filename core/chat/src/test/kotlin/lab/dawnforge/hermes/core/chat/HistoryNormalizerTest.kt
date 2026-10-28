package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import lab.dawnforge.hermes.core.model.ChatAttachment
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.ToolStatus

class HistoryNormalizerTest {
    @Test
    fun `an agent turn reads as reasoning, text and tool cards with their results`() {
        val items = normalizeHistory(
            rows(
                """
                [
                  {"id": 1, "role": "user", "content": "List the files", "timestamp": 1761050000.5},
                  {"id": 2, "role": "assistant", "content": "Let me look.", "reasoning": "Use ls.",
                   "tool_calls": [{"id": "call_a", "type": "function",
                     "function": {"name": "terminal", "arguments": "{\"command\": \"ls -la\"}"}}]},
                  {"id": 3, "role": "tool", "tool_call_id": "call_a", "tool_name": "terminal",
                   "content": "{\"output\": \"a.txt\\nb.txt\", \"exit_code\": 0}"},
                  {"id": 4, "role": "assistant", "content": "Two files: **a.txt** and b.txt."}
                ]
                """,
            ),
        )
        assertEquals(
            listOf(
                "user: List the files",
                "reasoning: Use ls.",
                "assistant: Let me look.",
                "tool terminal [Complete] ls -la -> a.txt\nb.txt",
                "assistant: Two files: **a.txt** and b.txt.",
            ),
            items.shown(),
        )
        assertEquals(listOf("row-1", "row-2-reasoning", "row-2", "tool-call_a", "row-4"), items.map { it.key })
        assertEquals(1_761_050_000_500, (items[0] as ChatItem.User).timestampMs)
        assertEquals("{\n    \"command\": \"ls -la\"\n}", (items[3] as ChatItem.Tool).input)
    }

    @Test
    fun `hidden rows are left out and Hermes' timeline rows become notes`() {
        val items = normalizeHistory(
            rows(
                """
                [
                  {"id": 1, "role": "user", "content": "[System: heartbeat]", "display_kind": "hidden"},
                  {"id": 2, "role": "user", "content": "[System: The active model for this chat has changed to x]",
                   "display_kind": "model_switch"},
                  {"id": 3, "role": "user", "content": "[OUT-OF-BAND USER MESSAGE]\nfaster please\n[/OUT-OF-BAND USER MESSAGE]",
                   "display_kind": "steer", "display_content": "faster please"},
                  {"id": 4, "role": "assistant", "content": "Hermes could not finish this turn.", "display_kind": "failed_turn",
                   "display_metadata": "{\"error\": \"Provider timed out\"}"},
                  {"id": 5, "role": "user", "content": "x", "display_kind": "process_complete",
                   "display_metadata": {"display_text": "Build finished"}},
                  {"id": 6, "role": "user", "content": "[CONTEXT COMPACTION]...", "display_content": "What next?"},
                  {"id": 7, "role": "system", "content": "You are Hermes."}
                ]
                """,
            ),
        )
        assertEquals(
            listOf(
                "notice ModelSwitched",
                "user: faster please",
                "notice Failed Provider timed out",
                "notice BackgroundProcessFinished Build finished",
                "user: What next?",
            ),
            items.shown(),
        )
        assertEquals(true, (items[1] as ChatItem.User).isSteer)
    }

    @Test
    fun `attachments are lifted out of the user's text and the expanded context is dropped`() {
        val user = assertIs<ChatItem.User>(
            normalizeHistory(
                rows(
                    """
                    [{"id": 9, "role": "user",
                      "content": "What is in this?\n@image:`/home/me/My Pics/cat.png`\n[screenshot]\nAlso @file:notes.md\n\n--- Attached Context ---\n\n📄 @file:notes.md (3 tokens)\n```\nhi\n```"}]
                    """,
                ),
            ).single(),
        )
        assertEquals("What is in this?\nAlso @file:notes.md", user.text)
        assertEquals(listOf(ChatAttachment("cat.png", "/home/me/My Pics/cat.png", isImage = true)), user.attachments)
    }

    @Test
    fun `an image sent with a file is found after the expanded file context`() {
        // As Hermes 0.22.2 stores a message sent with a file and an image: the reference, the text,
        // the expanded file, then the image line.
        val user = assertIs<ChatItem.User>(
            normalizeHistory(
                rows(
                    """
                    [{"id": 75, "role": "user",
                      "content": "@file:C:\\a\\check.pdf\n\nWhat is in these?\n\n--- Attached Context ---\n\n📄 @file:C:\\a\\check.pdf\nbinary file, not inlined\n@image:C:\\a\\images\\upload_1.png"}]
                    """,
                ),
            ).single(),
        )
        assertEquals("What is in these?", user.text)
        assertEquals(
            listOf("check.pdf" to false, "upload_1.png" to true),
            user.attachments.map { it.name to it.isImage },
        )
    }

    @Test
    fun `a call without a result was stopped, unless its turn is still running`() {
        val history = rows(
            """
            [
              {"id": 1, "role": "user", "content": "old"},
              {"id": 2, "role": "assistant", "content": "", "tool_calls": [{"id": "c1", "function": {"name": "web_search", "arguments": "{\"query\": \"a\"}"}}]},
              {"id": 3, "role": "user", "content": "new"},
              {"id": 4, "role": "assistant", "content": "", "tool_calls": [{"id": "c2", "function": {"name": "web_search", "arguments": "{}"}}]}
            ]
            """,
        )
        assertEquals(
            listOf(ToolStatus.Stopped, ToolStatus.Stopped),
            normalizeHistory(history).filterIsInstance<ChatItem.Tool>().map { it.status },
        )
        assertEquals(
            listOf(ToolStatus.Stopped, ToolStatus.Running),
            normalizeHistory(history, running = true).filterIsInstance<ChatItem.Tool>().map { it.status },
        )
    }

    @Test
    fun `failed calls, stopped replies and old reasoning tags`() {
        val items = normalizeHistory(
            rows(
                """
                [
                  {"id": 1, "role": "assistant", "content": null, "tool_calls": [{"id": "c1", "function": {"name": "terminal", "arguments": "{\"command\": \"false\"}"}}]},
                  {"id": 2, "role": "tool", "tool_call_id": "c1", "tool_name": "terminal", "content": "{\"output\": \"\", \"exit_code\": 1, \"error\": \"\\u001b[31mexit 1\\u001b[0m\"}"},
                  {"id": 3, "role": "assistant", "content": "<think>hmm</think>Half a repl", "display_metadata": {"interrupted": true}}
                ]
                """,
            ),
        )
        assertEquals(
            listOf("tool terminal [Failed] false -> exit 1", "assistant: Half a repl", "notice Stopped"),
            items.shown(),
        )
    }

    @Test
    fun `a result row whose call is on an older page still shows`() {
        val items = normalizeHistory(
            rows("""[{"id": 7, "role": "tool", "tool_call_id": "c9", "tool_name": "read_file", "content": "text"}]"""),
        )
        assertEquals(listOf("tool read_file [Complete]  -> text"), items.shown())
    }
}
