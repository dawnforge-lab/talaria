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

/** A Markdown table's separator row. */
private val TABLE_SEPARATOR = Regex("""\|?\s*:?-+:?\s*(?:\|\s*:?-+:?\s*)+\|?""")
