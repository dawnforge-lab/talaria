package lab.dawnforge.hermes.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetTest {
    @Test
    fun `marked matches are split out`() {
        assertEquals(
            listOf(
                SnippetPart("…we fixed the ", false),
                SnippetPart("gateway", true),
                SnippetPart(" and the ", false),
                SnippetPart("ticket", true),
            ),
            parseSnippet("…we fixed the >>>gateway<<< and the >>>ticket<<<"),
        )
    }

    @Test
    fun `snippets without markers stay whole`() {
        assertEquals(
            listOf(SnippetPart("Session title matched: ops", false)),
            parseSnippet("Session title matched: ops"),
        )
    }

    @Test
    fun `an unclosed marker is dropped`() {
        assertEquals(listOf(SnippetPart("abc def", false)), parseSnippet("abc >>>def"))
    }

    @Test
    fun `labels prefer the display name`() {
        assertEquals("Work", Profile("work", " Work ", false, null, null).label)
        assertEquals("work", Profile("work", "  ", false, null, null).label)
    }
}
