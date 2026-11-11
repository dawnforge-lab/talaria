package lab.dawnforge.hermes.core.chat

/**
 * Which of [lines] belong to a Markdown table: a row with a `|`, the separator row under it
 * (`|---|:-:|`, or `---|---` without the outer pipes), and the rows after it up to a blank line.
 * Tables in fenced code don't count.
 */
internal fun tableLines(lines: List<String>): BooleanArray {
    val table = BooleanArray(lines.size)
    var fence: CodeFence? = null
    var inTable = false
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        val open = fence
        val opening = if (open == null) CodeFence.openedBy(line) else null
        when {
            open != null -> if (open.closedBy(line)) fence = null

            opening != null -> {
                fence = opening
                inTable = false
            }

            inTable && trimmed.isNotEmpty() -> table[index] = true

            TABLE_SEPARATOR.matches(trimmed) && index > 0 && lines[index - 1].contains('|') -> {
                table[index - 1] = true
                table[index] = true
                inTable = true
            }

            else -> inTable = false
        }
    }
    return table
}

/**
 * For each of [lines], how far it must be indented to be code: four columns outside a list, and
 * inside a list item four past where the item's text starts. Fenced code doesn't move it.
 */
internal fun codeIndents(lines: List<String>): IntArray {
    val indents = IntArray(lines.size)
    var fence: CodeFence? = null
    var codeIndent = INDENTED_CODE
    lines.forEachIndexed { index, line ->
        val open = fence
        val opening = if (open == null) CodeFence.openedBy(line) else null
        val item = if (open == null) LIST_ITEM.find(line) else null
        when {
            open != null -> if (open.closedBy(line)) fence = null
            opening != null -> fence = opening
            item != null -> codeIndent = item.value.length + INDENTED_CODE
            line.isNotBlank() && !line.startsWith(' ') -> codeIndent = INDENTED_CODE
        }
        indents[index] = codeIndent
    }
    return indents
}

/**
 * Which of [lines] are indented code: indented as far as [codeIndents] says (a tab counts as four),
 * after a blank line or more such code. A line right after prose continues the prose instead.
 */
internal fun indentedCodeLines(lines: List<String>): BooleanArray {
    val indents = codeIndents(lines)
    val code = BooleanArray(lines.size)
    lines.forEachIndexed { index, line ->
        val indent = line.replace("\t", "    ").let { it.length - it.trimStart(' ').length }
        val afterBreak = index == 0 || lines[index - 1].isBlank() || code[index - 1]
        code[index] = line.isNotBlank() && indent >= indents[index] && afterBreak
    }
    return code
}

/** A list item's marker and the spaces after it, up to where its text starts. */
private val LIST_ITEM = Regex("""^ *(?:[-*+]|\d{1,9}[.)]) +(?=\S)""")

/** How far a line is indented to be code, outside a list. */
private const val INDENTED_CODE = 4

/** A Markdown table's separator row. */
private val TABLE_SEPARATOR = Regex("""\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)+\|?""")
