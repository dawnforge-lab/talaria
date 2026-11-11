package lab.dawnforge.hermes.core.chat

/**
 * A settled reply's Markdown, made ready for the chat's renderer, which draws no HTML and drops
 * what it reads as HTML. Outside code:
 * - `<br>` starts a new line (a space in a table row, where a line break would end the row);
 * - other HTML tags are taken out and their text kept, so a `<details>` block doesn't hide its text;
 * - what only looks like a tag (`Vec<u8>`, `List<String>`, `<your-key>`) stays as written;
 * - the common entities (`&amp;`, `&nbsp;`, …) become their characters.
 * Empty code blocks are dropped, and text laid out in columns goes into a code block
 * ([fenceAlignedText]). Hermes Desktop does the same before its renderer (`markdown-preprocess.ts`).
 */
fun prepareMarkdown(markdown: String): String = fenceAlignedText(cleanHtml(markdown))

private fun cleanHtml(markdown: String): String {
    if (!markdown.contains('<') && !markdown.contains('&') && !markdown.contains("```") &&
        !markdown.contains("~~~")
    ) {
        return markdown
    }
    val lines = markdown.lines()
    val table = tableLines(lines)
    val code = indentedCodeLines(lines)
    val out = mutableListOf<String>()
    var fence: CodeFence? = null
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val open = fence
        val opening = if (open == null) CodeFence.openedBy(line) else null
        if (open != null) {
            if (open.closedBy(line)) fence = null
            out += line
        } else if (opening != null) {
            val close = (index + 1 until lines.size).firstOrNull { opening.closedBy(lines[it]) }
            if (close != null && (index + 1 until close).all { lines[it].isBlank() }) {
                index = close + 1
                continue
            }
            fence = opening
            out += line
        } else if (code[index]) {
            out += line
        } else {
            out += cleanLine(line, tableRow = table[index])
        }
        index++
    }
    return out.joinToString("\n")
}

/** [line] with its HTML and entities dealt with, inline code left as it is. */
private fun cleanLine(source: String, tableRow: Boolean): String {
    if (!source.contains('<') && !source.contains('&')) return source
    // A `<br>` at either end of a line is the line's own break already.
    val line = if (tableRow) source else source.replace(EDGE_BREAK, "")
    val out = StringBuilder()
    var start = 0
    INLINE_CODE.findAll(line).forEach { code ->
        out.append(cleanProse(line.substring(start, code.range.first), tableRow)).append(code.value)
        start = code.range.last + 1
    }
    return out.append(cleanProse(line.substring(start), tableRow)).toString()
}

private fun cleanProse(text: String, tableRow: Boolean): String {
    val decoded = decodeEntities(text)
    return TAG.replace(decoded) { tag ->
        val name = tag.groupValues[1].lowercase()
        when {
            // Escaped already, by the model or from `&lt;`.
            decoded.getOrNull(tag.range.first - 1) == '\\' -> tag.value

            name == "br" -> if (tableRow) " " else "\n"

            name in HTML_ELEMENTS -> ""

            else -> "\\" + tag.value
        }
    }
}

private fun decodeEntities(text: String): String {
    if (!text.contains('&')) return text
    return ENTITY.replace(text) { entity ->
        when (entity.value.lowercase()) {
            "&amp;" -> "&"
            "&nbsp;" -> " "
            "&quot;" -> "\""
            "&#39;", "&apos;" -> "'"
            "&lt;" -> "\\<"
            "&gt;" -> ">"
            else -> entity.value
        }
    }
}

private val INLINE_CODE = Regex("`[^`\n]+`")
private val EDGE_BREAK = Regex("""^\s*<br\s*/?>|<br\s*/?>\s*$""", RegexOption.IGNORE_CASE)
private val ENTITY = Regex("&(?:amp|nbsp|quot|apos|lt|gt|#39);", RegexOption.IGNORE_CASE)

/** An opening or closing tag: a name, then attributes or nothing. */
private val TAG = Regex("""</?([A-Za-z][A-Za-z0-9-]*)(?:\s[^<>]*)?/?>""")

/** HTML's element names, so that a tag with any other name is text that only looks like one. */
private val HTML_ELEMENTS = setOf(
    "a", "abbr", "address", "article", "aside", "audio", "b", "bdi", "bdo", "big", "blockquote", "body",
    "br", "button", "caption", "center", "cite", "code", "col", "colgroup", "data", "dd", "del", "details",
    "dfn", "dialog", "div", "dl", "dt", "em", "embed", "fieldset", "figcaption", "figure", "font", "footer",
    "form", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hr", "html", "i", "iframe", "img",
    "input", "ins", "kbd", "label", "legend", "li", "link", "main", "mark", "meta", "nav", "noscript",
    "object", "ol", "optgroup", "option", "p", "picture", "pre", "q", "s", "samp", "script", "section",
    "select", "small", "source", "span", "strike", "strong", "style", "sub", "summary", "sup", "svg",
    "table", "tbody", "td", "textarea", "tfoot", "th", "thead", "time", "title", "tr", "tt", "u", "ul",
    "var", "video", "wbr",
)
