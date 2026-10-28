package lab.dawnforge.hermes.core.model

/** A piece of a search snippet: [match] when Hermes marked it as a hit. */
data class SnippetPart(val text: String, val match: Boolean)

/**
 * Splits a Hermes search snippet into plain and matched parts. Full-text hits mark matches as
 * `>>>term<<<`; other snippets have no markers and come back as one plain part.
 */
fun parseSnippet(snippet: String): List<SnippetPart> {
    val parts = mutableListOf<SnippetPart>()
    var rest = snippet
    while (rest.isNotEmpty()) {
        val open = rest.indexOf(OPEN)
        if (open < 0) {
            parts += SnippetPart(rest, match = false)
            break
        }
        val close = rest.indexOf(CLOSE, startIndex = open + OPEN.length)
        if (close < 0) {
            parts += SnippetPart(rest.replace(OPEN, ""), match = false)
            break
        }
        if (open > 0) parts += SnippetPart(rest.substring(0, open), match = false)
        val matched = rest.substring(open + OPEN.length, close)
        if (matched.isNotEmpty()) parts += SnippetPart(matched, match = true)
        rest = rest.substring(close + CLOSE.length)
    }
    return parts.filter { it.text.isNotEmpty() }
}

private const val OPEN = ">>>"
private const val CLOSE = "<<<"
