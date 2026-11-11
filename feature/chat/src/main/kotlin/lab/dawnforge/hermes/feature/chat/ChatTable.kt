package lab.dawnforge.hermes.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.TABLE_SEPARATOR

/**
 * A Markdown table that reads on a phone. Cells wrap instead of ending in "…", and each column is
 * as wide as its content up to [MAX_COLUMN], or as its longest word if that is wider. A table wider
 * than the chat narrows its columns towards their longest word, and past that scrolls sideways. The
 * renderer's own table gives every column 160dp and cuts each cell to one line.
 */
@Composable
internal fun ChatTable(content: String, node: ASTNode, style: TextStyle) {
    val table = remember(content, node) { tableOf(content, node) }
    if (table.columns == 0) return
    val shape = RoundedCornerShape(8.dp)
    val line = MaterialTheme.colorScheme.outlineVariant
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val settings = annotatorSettings()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val available = constraints.maxWidth
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            TableLayout(
                available = available,
                columns = table.columns,
                modifier = Modifier.clip(shape).border(1.dp, line, shape),
            ) {
                table.rows.forEachIndexed { rowIndex, row ->
                    val header = rowIndex == 0
                    row.forEachIndexed { column, cell ->
                        val align = table.alignments[column]
                        Box(
                            contentAlignment = align.box,
                            modifier = Modifier
                                .then(if (header) Modifier.background(headerBackground) else Modifier)
                                .cellLines(
                                    color = line,
                                    bottom = rowIndex < table.rows.lastIndex,
                                    end = column < table.columns - 1,
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            if (cell != null) {
                                MarkdownTableBasicText(
                                    content = content,
                                    cell = cell,
                                    style = style.copy(
                                        fontWeight = if (header) FontWeight.SemiBold else style.fontWeight,
                                        textAlign = align.text,
                                    ),
                                    maxLines = Int.MAX_VALUE,
                                    overflow = TextOverflow.Clip,
                                    annotatorSettings = settings,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays out [columns] cells per row, in order. Column widths come from the cells' intrinsic widths
 * (see [columnWidths]); each row is as tall as its tallest cell, and every cell fills its slot.
 */
@Composable
private fun TableLayout(available: Int, columns: Int, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { cells, _ ->
        val natural = IntArray(columns)
        val narrowest = IntArray(columns)
        cells.forEachIndexed { index, cell ->
            val column = index % columns
            natural[column] = maxOf(natural[column], cell.maxIntrinsicWidth(Constraints.Infinity))
            narrowest[column] = maxOf(narrowest[column], cell.minIntrinsicWidth(Constraints.Infinity))
        }
        val widths = columnWidths(natural, narrowest, available, MAX_COLUMN.roundToPx())
        val rows = cells.chunked(columns)
        val heights = rows.map { row ->
            row.withIndex().maxOf { (column, cell) -> cell.maxIntrinsicHeight(widths[column]) }
        }
        val placeables = rows.mapIndexed { rowIndex, row ->
            row.mapIndexed { column, cell -> cell.measure(Constraints.fixed(widths[column], heights[rowIndex])) }
        }
        layout(widths.sum(), heights.sum()) {
            var y = 0
            placeables.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEachIndexed { column, placeable ->
                    placeable.place(x, y)
                    x += widths[column]
                }
                y += heights[rowIndex]
            }
        }
    }
}

/**
 * Each column at its content's width, capped at [maxColumn] but never below its longest word
 * ([narrowest]). When they don't fit in [available],
 * the columns with the most room to wrap give it up first, down to their [narrowest] (the longest
 * word); a table still too wide keeps those widths and scrolls.
 */
internal fun columnWidths(natural: IntArray, narrowest: IntArray, available: Int, maxColumn: Int): IntArray {
    // A word longer than the cap widens its column, so the table scrolls rather than splitting it.
    val floor = IntArray(natural.size) { minOf(narrowest[it], natural[it]) }
    val wanted = IntArray(natural.size) { maxOf(minOf(natural[it], maxColumn), floor[it]) }
    val excess = wanted.sum() - available
    if (excess <= 0) return wanted
    val slack = IntArray(natural.size) { wanted[it] - floor[it] }
    val totalSlack = slack.sum()
    if (totalSlack <= excess) return floor
    val widths = IntArray(natural.size) { wanted[it] - (excess.toLong() * slack[it] / totalSlack).toInt() }
    // What integer division left over comes off the columns with room left, never below their floor.
    var over = widths.sum() - available
    for (column in widths.indices.sortedByDescending { widths[it] - floor[it] }) {
        if (over <= 0) break
        val take = minOf(over, widths[column] - floor[column])
        widths[column] -= take
        over -= take
    }
    return widths
}

/** A line under the cell and one at its end, so neighbouring cells draw the table's grid. */
private fun Modifier.cellLines(color: Color, bottom: Boolean, end: Boolean): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    if (bottom) {
        val y = size.height - stroke / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), stroke)
    }
    if (end) {
        val x = if (layoutDirection == LayoutDirection.Ltr) size.width - stroke / 2 else stroke / 2
        drawLine(color, Offset(x, 0f), Offset(x, size.height), stroke)
    }
}

internal enum class CellAlign(val box: Alignment, val text: TextAlign) {
    Start(Alignment.TopStart, TextAlign.Start),
    Center(Alignment.TopCenter, TextAlign.Center),
    End(Alignment.TopEnd, TextAlign.End),
}

/** A table's rows (the header first), each with one cell per column, empty where a row is short. */
internal class Table(val columns: Int, val rows: List<List<ASTNode?>>, val alignments: List<CellAlign>)

internal fun tableOf(content: String, node: ASTNode): Table {
    val header = node.children.firstOrNull { it.type == HEADER } ?: return Table(0, emptyList(), emptyList())
    val columns = header.children.count { it.type == CELL }
    val rows = (listOf(header) + node.children.filter { it.type == ROW }).map { row ->
        val cells = row.children.filter { it.type == CELL }
        List(columns) { cells.getOrNull(it) }
    }
    // The separator row (`|:--|--:|`) sets each column's alignment.
    val separator = node.children.firstOrNull { it.type == TABLE_SEPARATOR }?.getTextInNode(content)?.toString()
    val marks = separator?.trim()?.removePrefix("|")?.removeSuffix("|")?.split('|')?.map(String::trim).orEmpty()
    val alignments = List(columns) { column ->
        val mark = marks.getOrNull(column).orEmpty()
        when {
            mark.startsWith(':') && mark.endsWith(':') && mark.length > 1 -> CellAlign.Center
            mark.endsWith(':') -> CellAlign.End
            else -> CellAlign.Start
        }
    }
    return Table(columns, rows, alignments)
}

/** The widest a column gets before its cells wrap. */
private val MAX_COLUMN = 240.dp
