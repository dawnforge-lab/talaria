package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class AlignedTextTest {
    private fun fenced(block: String) = "```text\n$block\n```"

    @Test
    fun `a plain-text chart goes into a code block`() {
        val chart = """
            week    runs    minutes
            1          4    no data
            2         12    340 |################
            3          5    115 |#####
        """.trimIndent()
        assertEquals(
            "TRAINING LOG\n\n${fenced(chart)}\n\nWeek 2 was the longest.",
            fenceAlignedText("TRAINING LOG\n\n$chart\n\nWeek 2 was the longest."),
        )
    }

    @Test
    fun `columns sharing a start, rule lines and bars count`() {
        val twoColumns = "fruit     count\n--------  -----\napples    12\nbananas   7"
        assertEquals(fenced(twoColumns), fenceAlignedText(twoColumns))
        val bars = "Mon ███\nTue ██████"
        assertEquals(fenced(bars), fenceAlignedText(bars))
    }

    @Test
    fun `a block right after a sentence starts on its own fence`() {
        assertEquals(
            "By month:\n${fenced("jun  29\njul  83")}",
            fenceAlignedText("By month:\njun  29\njul  83"),
        )
    }

    @Test
    fun `prose, lists, quotes, headings and single lines stay as they are`() {
        listOf(
            "One sentence.  Another one.\nA third.  And a fourth.",
            "Two spaces  once here\nand  once there, not lined up.",
            "- a    b    c\n- d    e    f",
            "1. one    two    three\n2. four    five    six",
            "> a    b    c\n> d    e    f",
            "## Totals    by    month",
            "only    one    line",
            "Hard line break  \nnext line",
        ).forEach { text -> assertEquals(text, fenceAlignedText(text)) }
    }

    @Test
    fun `code, math and Markdown tables are left alone`() {
        listOf(
            "```\na    b    c\nd    e    f\n```",
            "~~~\na    b    c\nd    e    f\n~~~",
            "$$\na    &    b \\\\\nc    &    d\n$$",
            "| a    | b    |\n|------|------|\n| 1    | 2    |",
            "a    |  b\n---|---\nc    |  d\ne    |  f",
            "    indented    code    block\n    more    code    here",
        ).forEach { text -> assertEquals(text, fenceAlignedText(text)) }
    }
}
