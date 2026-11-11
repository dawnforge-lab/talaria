package lab.dawnforge.hermes.core.chat

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownCleanupTest {
    @Test
    fun `line breaks in HTML start a new line, or leave a space in a table row`() {
        assertEquals("line one\nline two", prepareMarkdown("line one<br>line two"))
        assertEquals("a\nb\nc", prepareMarkdown("a<br/>b<br />c"))
        assertEquals(
            "| a | b |\n|---|---|\n| one two | x |",
            prepareMarkdown("| a | b |\n|---|---|\n| one<br>two | x |"),
        )
        // A table without its outer pipes, and the header row too.
        assertEquals("a b | c\n---|---\nd e | f", prepareMarkdown("a<br>b | c\n---|---\nd<br>e | f"))
        // A line with a pipe that isn't in a table is prose.
        assertEquals("x | y\nz", prepareMarkdown("x | y<br>z"))
    }

    @Test
    fun `HTML tags go and their text stays`() {
        assertEquals(
            "\nResults\nThe hidden text\n",
            prepareMarkdown("<details>\n<summary>Results</summary>\nThe hidden text\n</details>"),
        )
        assertEquals(
            "A bold claim, underlined.",
            prepareMarkdown("A <b>bold</b> claim, <u class=\"x\">underlined</u>."),
        )
    }

    @Test
    fun `what only looks like a tag stays as written`() {
        assertEquals("Use Vec\\<u8> and List\\<String>.", prepareMarkdown("Use Vec<u8> and List<String>."))
        assertEquals("Set \\<your-key> in .env", prepareMarkdown("Set <your-key> in .env"))
        assertEquals("Already \\<escaped>", prepareMarkdown("Already \\<escaped>"))
        assertEquals(
            "See <https://example.com> or <me@example.com>",
            prepareMarkdown("See <https://example.com> or <me@example.com>"),
        )
    }

    @Test
    fun `entities become characters, and an escaped tag stays text`() {
        assertEquals("AT&T, a b, \"q\", it's", prepareMarkdown("AT&amp;T, a&nbsp;b, &quot;q&quot;, it&#39;s"))
        assertEquals("\\<b> is bold", prepareMarkdown("&lt;b&gt; is bold"))
    }

    @Test
    fun `code keeps its HTML and entities`() {
        assertEquals("Use `Vec<u8>` and `a&amp;b`", prepareMarkdown("Use `Vec<u8>` and `a&amp;b`"))
        val fence = "```html\n<div>&amp;</div>\n```"
        assertEquals(fence, prepareMarkdown(fence))
    }

    @Test
    fun `empty code blocks are dropped`() {
        assertEquals("Before\nAfter", prepareMarkdown("Before\n```\n```\nAfter"))
        assertEquals("Before\nAfter", prepareMarkdown("Before\n```python\n\n```\nAfter"))
        assertEquals("```\ncode\n```", prepareMarkdown("```\ncode\n```"))
    }
}
