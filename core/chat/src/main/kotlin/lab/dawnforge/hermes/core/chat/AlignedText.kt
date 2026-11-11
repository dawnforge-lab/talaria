package lab.dawnforge.hermes.core.chat

/**
 * Puts text laid out in columns into a `text` code fence, so its columns line up in a fixed-width font.
 * Models sometimes write a small chart or table as plain text (`week   runs   minutes`,
 * `2   12   340 |#####`), which Markdown would show as wrapped prose.
 *
 * A block is two or more lines in a row with a gap of two or more spaces between words, that also
 * share a column (a word starting at the same place on every line), or each have two such gaps, or
 * of which two or more draw a bar (`|####`, `████`, at the start of the line or after something).
 * A rule line (`-----  -----`) between them belongs to the block. A gap after a full stop is a
 * sentence break, not a column. Code, display math, lists, quotes, headings and Markdown tables are
 * left alone. Each line is measured once, so a long run of lines that never line up stays cheap.
 */
fun fenceAlignedText(markdown: String): String {
    if (!markdown.contains("  ") && !markdown.contains('\t') && !BAR.containsMatchIn(markdown)) return markdown
    val lines = markdown.lines()
    val shapes = lineShapes(lines)
    val out = mutableListOf<String>()
    var index = 0
    var end = 0
    while (index < lines.size) {
        // The run of lines that can be columns, from here on (the same run for every line in it).
        if (end <= index) {
            end = index
            while (end < lines.size && shapes[end].kind != LineKind.Other) end++
        }
        val block = lines.subList(index, end)
        if (end > index && isAlignedBlock(shapes.subList(index, end))) {
            // At the block's own indent, so a chart under a list item stays in that item.
            val indent = " ".repeat(block.minOf { line -> line.length - line.trimStart(' ').length })
            out += "$indent```text"
            block.forEach { out += it.trimEnd() }
            out += "$indent```"
            index = end
        } else {
            out += lines[index]
            index++
        }
    }
    return out.joinToString("\n")
}

private enum class LineKind { Columns, Rule, Other }

/** A line as the detector sees it: what it can be part of, where its gaps end, and whether it has a bar. */
private class LineShape(val kind: LineKind, val gapEnds: Set<Int> = emptySet(), val bar: Boolean = false)

private val OTHER = LineShape(LineKind.Other)

/** Each line's shape. Code, display math and Markdown tables are never columns. */
private fun lineShapes(lines: List<String>): List<LineShape> {
    val shapes = MutableList(lines.size) { OTHER }
    val table = tableLines(lines)
    val indents = codeIndents(lines)
    var fence: CodeFence? = null
    var math = false
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        val open = fence
        val opening = if (open == null) CodeFence.openedBy(line) else null
        when {
            open != null -> if (open.closedBy(line)) fence = null
            opening != null -> fence = opening
            trimmed == "$$" -> math = !math
            math || table[index] -> Unit
            else -> shapes[index] = shapeOf(line, indents[index])
        }
    }
    return shapes
}

/** [line]'s shape; indented [codeIndent] spaces or more, or by a tab, it is code. */
private fun shapeOf(line: String, codeIndent: Int): LineShape {
    val text = line.trimEnd()
    val indent = text.length - text.trimStart(' ').length
    if (text.isBlank() || indent >= codeIndent || text.startsWith('\t')) return OTHER
    val trimmed = text.trimStart()
    if (NOT_COLUMNS.containsMatchIn(trimmed)) return OTHER
    if (RULE.matches(trimmed)) return LineShape(LineKind.Rule)
    val gapEnds = GAP.findAll(text).map { it.range.last + 1 }.toSet()
    val bar = BAR.containsMatchIn(text)
    return if (gapEnds.isNotEmpty() || bar) LineShape(LineKind.Columns, gapEnds, bar) else OTHER
}

private fun isAlignedBlock(run: List<LineShape>): Boolean {
    val columns = run.filter { it.kind == LineKind.Columns }
    if (columns.size < 2) return false
    if (columns.count { it.bar } >= 2) return true
    if (columns.all { it.gapEnds.size >= 2 }) return true
    return columns.map { it.gapEnds }.reduce { common, ends -> common intersect ends }.isNotEmpty()
}

/** Two or more spaces, or a tab, between two words; not after the end of a sentence. */
private val GAP = Regex("""(?<=[^\s.!?])(?: {2,}|\t+)(?=\S)""")

/** A bar: three or more block characters, or `|###`. */
private val BAR = Regex("""[█▉▊▋▌▍▎▏▓▒░■▇▆▅▄▃▂▁]{3,}|\|\s*#{3,}""")

/** A line of rule characters with a gap in it, as under a plain-text table's header. */
private val RULE = Regex("""[-=_+|: ]*[-=_][-=_+|: ]* {2,}[-=_+|: ]*""")

/** Lists, quotes, headings and table rows. */
private val NOT_COLUMNS = Regex("""^(?:[-*+]\s|\d+[.)]\s|>|#{1,6}(?:\s|$)|\|)""")
