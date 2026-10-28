package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplyMediaTest {
    private fun media(path: String, kind: MediaKind, label: String? = null) = ReplyPart.Media(path, label, kind)

    @Test
    fun `tags split the reply around the files they name`() {
        val parts = splitReply(
            """
            Here is the chart:
            MEDIA:/home/u/out/chart.png
            And the voice note [[audio_as_voice]]
            MEDIA:/home/u/.hermes/audio_cache/tts_1.ogg
            Done.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                ReplyPart.Markdown("Here is the chart:"),
                media("/home/u/out/chart.png", MediaKind.Image),
                ReplyPart.Markdown("And the voice note"),
                media("/home/u/.hermes/audio_cache/tts_1.ogg", MediaKind.Audio),
                ReplyPart.Markdown("Done."),
            ),
            parts,
        )
    }

    @Test
    fun `quoted paths, Markdown labels and mid-line tags are files too`() {
        assertEquals(
            listOf(ReplyPart.Markdown("Here it is:"), media("/tmp/My Chart.png", MediaKind.Image)),
            splitReply("""Here it is: MEDIA:"/tmp/My Chart.png"."""),
        )
        assertEquals(
            listOf(media("/tmp/report.pdf", MediaKind.File, label = "the report")),
            splitReply("![the report](MEDIA:/tmp/report.pdf)"),
        )
        assertEquals(
            listOf(media("C:\\Users\\me\\clip.mp4", MediaKind.Video)),
            splitReply("MEDIA:C:\\Users\\me\\clip.mp4"),
        )
    }

    @Test
    fun `tags in code, inline code or a quote stay text`() {
        val code = "```\nMEDIA:/tmp/a.png\n```"
        assertEquals(listOf(ReplyPart.Markdown(code)), splitReply(code))
        val inline = "Write `MEDIA:/tmp/a.png` to send a file."
        assertEquals(listOf(ReplyPart.Markdown(inline)), splitReply(inline))
        val quote = "> MEDIA:/tmp/a.png"
        assertEquals(listOf(ReplyPart.Markdown(quote)), splitReply(quote))
        assertEquals(listOf(ReplyPart.Markdown("No files here.")), splitReply("No files here."))
    }

    @Test
    fun `kinds come from the extension`() {
        assertEquals(MediaKind.Image, mediaKind("/a/b.JPG"))
        assertEquals(MediaKind.Audio, mediaKind("/a/b.opus"))
        assertEquals(MediaKind.Video, mediaKind("/a/b.webm"))
        assertEquals(MediaKind.Page, mediaKind("/a/b.html"))
        assertEquals(MediaKind.File, mediaKind("/a/b.csv"))
        assertEquals(MediaKind.File, mediaKind("/a/noext"))
    }

    @Test
    fun `session references become links the chat opens, outside code`() {
        assertEquals(
            "See [Open chat](hermes-session://open?id=20261022_1_abc&profile=work) for that.",
            linkSessions("See @session:work/20261022_1_abc for that.", "Open chat"),
        )
        assertEquals(
            "[that chat](hermes-session://open?id=s1)",
            linkSessions("[that chat](@session:s1)", "Open chat"),
        )
        assertEquals("`@session:s1`", linkSessions("`@session:s1`", "Open chat"))
        assertEquals("mail@session:s1", linkSessions("mail@session:s1", "Open chat"))
        assertEquals("work" to "abc", parseSessionLink("hermes-session://open?id=abc&profile=work"))
        assertEquals(null to "abc", parseSessionLink("hermes-session://open?id=abc"))
        assertNull(parseSessionLink("https://example.com"))
    }

    @Test
    fun `a review lists what it changed`() {
        assertEquals(
            "- Memory ➕ likes tea\n- Skill ✏️ deploy",
            reviewText("💾 Self-improvement review: Memory ➕ likes tea · Skill ✏️ deploy"),
        )
    }
}

class TodosTest {
    @Test
    fun `a todo result reads as a plan, subtasks nested and duplicates dropped`() {
        val todos = parseTodos(
            """{"todos":[{"id":"1","content":"Plan","status":"completed"},
                {"id":"2","content":"Build","status":"in_progress"},
                {"id":"3","content":"Tests","status":"pending","parent":"2"},
                {"id":"3","content":"again","status":"pending"},
                {"id":"4","content":"Skip","status":"cancelled","parent":"0"}],
               "revision":3,"summary":{"total":4}}""",
        )!!
        assertEquals(listOf("1", "2", "3", "4"), todos.map { it.id })
        assertEquals(
            listOf(TodoStatus.Completed, TodoStatus.InProgress, TodoStatus.Pending, TodoStatus.Cancelled),
            todos.map {
                it.status
            },
        )
        assertEquals(listOf(0, 0, 1, 0), todos.map { it.depth })
        assertNull(parseTodos("""{"error":"bad"}"""))
        assertNull(parseTodos("not json"))
    }
}
