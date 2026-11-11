package lab.dawnforge.hermes.core.chat

/**
 * Puts text laid out in columns into a `text` code fence, so its columns line up in a fixed-width font.
 * Models sometimes write a small chart or table as plain text (`week   runs   minutes`,
 * `2   12   340 |#####`), which Markdown would show as wrapped prose.
 *
 * A block is two or more lines in a row with a gap of two or more spaces between words, that also
 * share a column (a word starting at the same place on every line), or each have two such gaps, or
 * draw a bar (`|####`, `████`). A rule line (`-----  -----`) between them belongs to the block. A
 * gap after a full stop is a sentence break, not a column. Code, display math, lists, quotes,
 * headings and Markdown tables are left alone.
 */
fun fenceAlignedText(markdown: String): String {
    if (!markdown.contains("  ") && !markdown.contains('\t') && !BAR.containsMatchIn(markdown)) return markdown
    val lines = markdown.lines()
    val kinds = lineKinds(lines)
    val out = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        var end = index
        while (end < lines.size && kinds[end] != LineKind.Other) end++
        val block = lines.subList(index, end)
        if (end > index && isAlignedBlock(block, kinds.subList(index, end))) {
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

/** What each line can be part of: code, display math and Markdown tables are never columns. */
private fun lineKinds(lines: List<String>): List<LineKind> {
    val kinds = MutableList(lines.size) { LineKind.Other }
    val table = tableLines(lines)
    var fence: CodeFence? = null
    var math = false
    // Inside a list item its text is indented as far as the item's text starts, and code 4 more.
    var codeIndent = INDENTED_CODE
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        val open = fence
        val opening = if (open == null) CodeFence.openedBy(line) else null
        val item = LIST_ITEM.find(line)
        when {
            open != null -> if (open.closedBy(line)) fence = null

            opening != null -> fence = opening

            trimmed == "$$" -> math = !math

            math || table[index] -> Unit

            item != null -> codeIndent = item.value.length + INDENTED_CODE

            else -> {
                if (trimmed.isNotEmpty() && !line.startsWith(' ')) codeIndent = INDENTED_CODE
                kinds[index] = kindOf(line, codeIndent)
            }
        }
    }
    return kinds
}

/** [line]'s kind; indented [codeIndent] spaces or more, or by a tab, it is code. */
private fun kindOf(line: String, codeIndent: Int): LineKind {
    val text = line.trimEnd()
    val indent = text.length - text.trimStart(' ').length
    if (text.isBlank() || indent >= codeIndent || text.startsWith('\t')) return LineKind.Other
    val trimmed = text.trimStart()
    if (NOT_COLUMNS.containsMatchIn(trimmed)) return LineKind.Other
    if (RULE.matches(trimmed)) return LineKind.Rule
    return if (GAP.containsMatchIn(text) || BAR.containsMatchIn(text)) LineKind.Columns else LineKind.Other
}

private fun isAlignedBlock(run: List<String>, kinds: List<LineKind>): Boolean {
    val columns = run.filterIndexed { index, _ -> kinds[index] == LineKind.Columns }.map(String::trimEnd)
    if (columns.size < 2) return false
    if (columns.any { BAR.containsMatchIn(it) }) return true
    if (columns.all { line -> GAP.findAll(line).count() >= 2 }) return true
    val shared = columns.map { line -> GAP.findAll(line).map { it.range.last + 1 }.toSet() }
        .reduce { common, starts -> common intersect starts }
    return shared.isNotEmpty()
}

/** A list item's marker and the spaces after it, up to where its text starts. */
private val LIST_ITEM = Regex("""^ *(?:[-*+]|\d{1,9}[.)]) +(?=\S)""")

/** How far a line is indented to be code, outside a list. */
private const val INDENTED_CODE = 4

/** Two or more spaces, or a tab, between two words; not after the end of a sentence. */
private val GAP = Regex("""(?<=[^\s.!?])(?: {2,}|\t+)(?=\S)""")

/** A bar of three or more block characters after something else on the line. */
private val BAR = Regex("""\S.*?[#█▉▊▋▌▍▎▏▓▒░■▇▆▅▄▃▂▁]{3,}""")

/** A line of rule characters with a gap in it, as under a plain-text table's header. */
private val RULE = Regex("""[-=_+|: ]*[-=_][-=_+|: ]* {2,}[-=_+|: ]*""")

/** Lists, quotes, headings and table rows. */
private val NOT_COLUMNS = Regex("""^(?:[-*+]\s|\d+[.)]\s|>|#{1,6}(?:\s|$)|\|)""")
