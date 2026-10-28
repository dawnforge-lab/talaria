package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyBlocksTest {
    @Test
    fun `display formulas are split out, one line or several`() {
        assertEquals(
            listOf(ReplyPart.Markdown("Euler:"), ReplyPart.Math("e^{i\\pi} + 1 = 0"), ReplyPart.Markdown("Neat.")),
            splitReply("Euler:\n$$ e^{i\\pi} + 1 = 0 $$\nNeat."),
        )
        assertEquals(
            listOf(ReplyPart.Math("a^2 + b^2\n= c^2")),
            splitReply("$$\na^2 + b^2\n= c^2\n$$"),
        )
        assertEquals(listOf(ReplyPart.Math("x = 1")), splitReply("\\[\nx = 1\n\\]"))
        // Inline dollars stay text, as on iOS.
        assertEquals(listOf(ReplyPart.Markdown("It costs $5 or $$")), splitReply("It costs $5 or $$"))
    }

    @Test
    fun `mermaid fences become diagrams, other fences stay code`() {
        assertEquals(
            listOf(ReplyPart.Markdown("Flow:"), ReplyPart.Diagram("graph TD\n  A --> B")),
            splitReply("Flow:\n```mermaid\ngraph TD\n  A --> B\n```"),
        )
        val code = "```kotlin\nval x = \"\$\$\"\n```"
        assertEquals(listOf(ReplyPart.Markdown(code)), splitReply(code))
    }

    @Test
    fun `previews of pages are drawn, other files become cards`() {
        assertEquals(
            listOf(ReplyPart.Markdown("Here:"), ReplyPart.Page("/home/u/out/chart.html")),
            splitReply("Here: ::preview{file=\"/home/u/out/chart.html\"}"),
        )
        assertEquals(
            listOf(ReplyPart.Media("/tmp/data.csv", null, MediaKind.File)),
            splitReply("::preview{file='/tmp/data.csv'}"),
        )
        val quoted = "> ::preview{file=\"/a.html\"}"
        assertEquals(listOf(ReplyPart.Markdown(quoted)), splitReply(quoted))
    }

    @Test
    fun `a block left open stays text`() {
        assertEquals(listOf(ReplyPart.Markdown("$$\nx = 1")), splitReply("$$\nx = 1"))
    }
}
