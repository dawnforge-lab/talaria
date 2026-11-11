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
        // A table of one column.
        assertEquals("| a |\n|---|\n| one two |", prepareMarkdown("| a |\n|---|\n| one<br>two |"))
        // A line with a pipe that isn't in a table is prose.
        assertEquals("x | y\nz", prepareMarkdown("x | y<br>z"))
        // At either end of a line it is that line's own break, not a blank line.
        assertEquals("one\ntwo", prepareMarkdown("one<br>\ntwo"))
        assertEquals("one\ntwo", prepareMarkdown("one\n<br/>two"))
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
        // One-letter type parameters that share a name with an element (`<s>`, `<a>`, `<b>`).
        assertEquals("Box\\<S>, List\\<A>, Foo\\<B>", prepareMarkdown("Box<S>, List<A>, Foo<B>"))
        assertEquals("one\ntwo", prepareMarkdown("one<BR>two"))
        // In lower case too, and words after an element's name that aren't attributes.
        assertEquals("Box\\<s> and Foo\\<a>", prepareMarkdown("Box<s> and Foo<a>"))
        assertEquals("if a \\<b and c> d", prepareMarkdown("if a <b and c> d"))
        // A closing tag shown in code closes nothing, so it doesn't make a look-alike HTML.
        assertEquals("Box\\<b> here, and `</b>` in code", prepareMarkdown("Box<b> here, and `</b>` in code"))
        assertEquals("```\n</b>\n```\nFoo\\<b>", prepareMarkdown("```\n</b>\n```\nFoo<b>"))
        // Attributes that stand alone are attributes.
        assertEquals(
            "\nShown\nInside\n",
            prepareMarkdown("<details open>\n<summary>Shown</summary>\nInside\n</details>"),
        )
        // Real HTML still goes: closed later, empty, with attributes, or a closing tag.
        assertEquals("x  y z", prepareMarkdown("<i>x</i> <hr> <span id=\"n\">y</span> z</div>"))
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
    fun `indented code keeps its HTML and entities, an indented line after prose doesn't`() {
        val code = "Example:\n\n    <div class=\"x\">a &amp; b</div>\n\n    <br>\n\nAfter <b>that</b>"
        assertEquals(
            "Example:\n\n    <div class=\"x\">a &amp; b</div>\n\n    <br>\n\nAfter that",
            prepareMarkdown(code),
        )
        assertEquals("Prose\n    continued", prepareMarkdown("Prose\n    <b>continued</b>"))
        // In a list item, code is indented four past the item's text.
        assertEquals("1. Item\n\n       <b>code</b>", prepareMarkdown("1. Item\n\n       <b>code</b>"))
        assertEquals("1. Item\n\n   text", prepareMarkdown("1. Item\n\n   <b>text</b>"))
    }

    @Test
    fun `empty code blocks are dropped`() {
        assertEquals("Before\nAfter", prepareMarkdown("Before\n```\n```\nAfter"))
        assertEquals("Before\nAfter", prepareMarkdown("Before\n```python\n\n```\nAfter"))
        assertEquals("```\ncode\n```", prepareMarkdown("```\ncode\n```"))
        // A fence with an info string never closes a block, and a longer fence holds shorter ones.
        val nested = "````markdown\n```kotlin\nval x = 1\n```\n````"
        assertEquals(nested, prepareMarkdown(nested))
        assertEquals("```\n```kotlin\n<b>\n```", prepareMarkdown("```\n```kotlin\n<b>\n```"))
    }
}
