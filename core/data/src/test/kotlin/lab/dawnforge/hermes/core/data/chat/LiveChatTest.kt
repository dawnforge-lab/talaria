package lab.dawnforge.hermes.core.data.chat

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import lab.dawnforge.hermes.core.chat.normalizeHistory
import lab.dawnforge.hermes.core.data.LiveHermes
import lab.dawnforge.hermes.core.model.ChatItem
import lab.dawnforge.hermes.core.model.Profile
import lab.dawnforge.hermes.core.net.NetResult
import org.junit.Assume.assumeTrue

/**
 * A chat's history and live session against a real Hermes dashboard. Skipped unless
 * `-Phermes.live.file` is set. It reads only a chat it creates itself (seeded, so no model is
 * called) and deletes it afterwards.
 */
class LiveChatTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `pages a chat's history from the newest end and opens it on the live connection`() = runBlocking {
        assumeTrue("set -Phermes.live.file to run against a real Hermes", LiveHermes.config != null)
        val live = LiveHermes.connect(scope)
        val marker = "androidcheck" + UUID.randomUUID().toString().take(6)
        val filler = (1..12).flatMap { n -> listOf("user" to "Question $n ($marker)", "assistant" to "Answer $n.") }
        val messages = listOf(
            "user" to "Show me some Markdown ($marker)",
            "assistant" to "Here is code:\n\n```kotlin\nval x = 1\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |",
            "hidden" to "[System: scaffolding the app must not show]",
            "user" to "What is in this?\n@image:/tmp/android-check.png\n[screenshot]",
            "assistant" to "A test image.",
        ) + filler
        val storedId = assertNotNull(
            (
                LiveHermes.seed(
                    live.gateway,
                    "Android client test $marker",
                    messages,
                )["stored_session_id"] as? JsonPrimitive
                )
                ?.contentOrNull,
        )
        println("live: created test chat $storedId with ${messages.size} rows")

        try {
            // Pages of 10 from the newest end, each oldest-first, until Hermes runs out.
            val pages = mutableListOf<HistoryPage>()
            var offset = 0
            do {
                val page = assertIs<NetResult.Success<HistoryPage>>(
                    live.chats.history(live.id, Profile.DEFAULT, storedId, offset = offset, limit = 10),
                ).value
                pages += page
                offset += page.returned
            } while (page.hasMore && pages.size < 10)
            val rows: List<JsonObject> = pages.asReversed().flatMap { it.rows }
            println("live: ${pages.size} pages, ${rows.size} rows; page sizes ${pages.map { it.returned }}")
            assertEquals(storedId, pages.first().sessionId)
            assertEquals(messages.size, rows.size, "every stored row, the hidden one too: ${LiveHermes.describe(rows)}")

            val items = normalizeHistory(rows)
            assertEquals(messages.size - 1, items.size, "the hidden row is not shown")
            assertEquals("Show me some Markdown ($marker)", (items.first() as ChatItem.User).text)
            assertTrue((items[1] as ChatItem.Assistant).text.contains("```kotlin"))
            val withImage = items[2] as ChatItem.User
            assertEquals("What is in this?", withImage.text)
            assertEquals("/tmp/android-check.png", withImage.attachments.single().path)
            assertEquals("Answer 12.", (items.last() as ChatItem.Assistant).text)
            assertTrue(items.all { it.key.startsWith("row-") }, "rows keep their stored ids as keys")
            val times = items.mapNotNull { (it as? ChatItem.User)?.timestampMs }
            assertTrue(times.isNotEmpty() && times.all { it > 1_600_000_000_000 }, "timestamps are epoch ms")

            // One page of the default size holds it all.
            val whole = assertIs<NetResult.Success<HistoryPage>>(
                live.chats.history(live.id, Profile.DEFAULT, storedId),
            ).value
            assertEquals(rows, whole.rows)
            assertFalse(whole.hasMore)

            val opened = assertIs<NetResult.Success<OpenedChat>>(live.chats.open(Profile.DEFAULT, storedId)).value
            println("live: opened as ${opened.runtimeId}, running=${opened.running}, title=${opened.title}")
            assertEquals(storedId, opened.storedId)
            assertFalse(opened.running)
            live.chats.leave(opened.runtimeId)
        } finally {
            assertIs<NetResult.Success<Unit>>(live.sessions.delete(live.id, Profile.DEFAULT, storedId))
            println("live: deleted the test chat")
            live.gateway.stop()
        }
    }

    /** With `-Phermes.live.seed=N` it also leaves one chat with rich Markdown, for checking the chat screen by hand. */
    @Test
    fun `seeds a chat with Markdown for a manual check`() = runBlocking {
        assumeTrue(
            "set -Phermes.live.seed=N to leave test chats",
            LiveHermes.config != null && System.getProperty("hermes.live.seed") != null,
        )
        val live = LiveHermes.connect(scope)
        val marker = "androidcheck" + UUID.randomUUID().toString().take(6)
        val reply = listOf(
            "## Results",
            "",
            "Here is **bold**, *italic*, `inline code` and a [link](https://example.com).",
            "",
            "1. First",
            "2. Second",
            "   - nested",
            "",
            "- [x] done",
            "- [ ] to do",
            "",
            "> A quote from the docs.",
            "",
            "```kotlin",
            "fun greet(name: String) = println(\"Hello, \$name\")",
            "```",
            "",
            "| Tool | Calls |",
            "|------|------:|",
            "| terminal | 3 |",
            "| web_search | 1 |",
        ).joinToString("\n")
        val created = LiveHermes.seed(
            live.gateway,
            "Android client test Markdown",
            listOf(
                "user" to "Show me what you can render ($marker)",
                "assistant" to reply,
                "user" to "And an image?\n@image:/tmp/android-check.png\n[screenshot]",
                "assistant" to "That is a test image.",
            ),
        )
        println("live: seeded ${(created["stored_session_id"] as? JsonPrimitive)?.contentOrNull} ($marker)")
        live.gateway.stop()
    }
}
