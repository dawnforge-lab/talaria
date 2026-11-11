package lab.dawnforge.hermes.feature.chat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

class ChatTableTest {
    @Test
    fun `a table that fits keeps its columns at their content's width, capped`() {
        val widths = columnWidths(
            natural = intArrayOf(80, 900),
            narrowest = intArrayOf(40, 90),
            available = 1000,
            maxColumn = 240,
        )
        assertContentEquals(intArrayOf(80, 240), widths)
    }

    @Test
    fun `a table too wide narrows the columns with most room to wrap, to fill the width exactly`() {
        val widths = columnWidths(
            natural = intArrayOf(100, 300, 300),
            narrowest = intArrayOf(100, 60, 120),
            available = 500,
            maxColumn = 400,
        )
        // The middle column had 240 to give and the last 180: 200 comes off in that proportion.
        assertContentEquals(intArrayOf(100, 185, 215), widths)
    }

    @Test
    fun `what rounding leaves over never takes a column below its longest word`() {
        val widths = columnWidths(
            natural = intArrayOf(50, 50, 50),
            narrowest = intArrayOf(49, 49, 49),
            available = 148,
            maxColumn = 240,
        )
        assertEquals(148, widths.sum())
        assertTrue(widths.all { it >= 49 }, widths.joinToString())
    }

    @Test
    fun `a word longer than the cap widens its column`() {
        val widths = columnWidths(
            natural = intArrayOf(80, 900),
            narrowest = intArrayOf(40, 300),
            available = 1000,
            maxColumn = 240,
        )
        assertContentEquals(intArrayOf(80, 300), widths)
    }

    @Test
    fun `past the longest words the table keeps them and scrolls`() {
        val widths = columnWidths(
            natural = intArrayOf(400, 400),
            narrowest = intArrayOf(150, 200),
            available = 300,
            maxColumn = 500,
        )
        assertContentEquals(intArrayOf(150, 200), widths)
    }

    @Test
    fun `a table has the header's columns, each aligned as its separator says`() {
        val text = "| a | b | c |\n|:--|:-:|--:|\n| 1 | 2 | 3 |"
        val table = tableOf(text, tableNode(text))
        assertEquals(3, table.columns)
        assertEquals(listOf(CellAlign.Start, CellAlign.Center, CellAlign.End), table.alignments)
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), table.rows.map { cells(text, it) })
    }

    @Test
    fun `a short row gets empty cells and a long row's extra cells go`() {
        val text = "| a | b |\n|---|---|\n| 1 |\n| x | y | z |"
        val table = tableOf(text, tableNode(text))
        assertEquals(listOf(CellAlign.Start, CellAlign.Start), table.alignments)
        assertEquals(listOf("1", null), cells(text, table.rows[1]))
        assertEquals(listOf("x", "y"), cells(text, table.rows[2]))
    }

    @Test
    fun `a table without its outer pipes reads the same`() {
        val text = "a | b\n--:|---\n1 | 2"
        val table = tableOf(text, tableNode(text))
        assertEquals(2, table.columns)
        assertEquals(listOf(CellAlign.End, CellAlign.Start), table.alignments)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), table.rows.map { cells(text, it) })
    }

    private fun tableNode(text: CharSequence): ASTNode =
        MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(text).children
            .first { it.type == GFMElementTypes.TABLE }

    private fun cells(text: String, row: List<ASTNode?>) = row.map { it?.getTextInNode(text)?.toString()?.trim() }
}
