package lab.dawnforge.hermes.core.chat

/**
 * A settled reply's Markdown, made ready for the chat's renderer, which draws no HTML and drops
 * what it reads as HTML. Outside code:
 * - `<br>` starts a new line (a space in a table row, where a line break would end the row);
 * - other HTML tags are taken out and their text kept, so a `<details>` block doesn't hide its
 *   text. A tag is HTML when it is written in lower case with an element's name, and is a closing
 *   tag, an empty element (`<hr>`), has attributes (`<div class="x">`, `<details open>`) or is
 *   closed somewhere in the reply's prose;
 * - what only looks like a tag stays as written: `Vec<u8>`, `List<String>`, `<your-key>`, and
 *   `Box<S>`, `Box<s>` or `a <b and c> d`, which share a name with an element but are none of those;
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
    val closed = closingTags(lines, code)
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
            out += cleanLine(line, tableRow = table[index], closed = closed)
        }
        index++
    }
    return out.joinToString("\n")
}

/** [line] with its HTML and entities dealt with, inline code left as it is. */
private fun cleanLine(source: String, tableRow: Boolean, closed: Set<String>): String {
    if (!source.contains('<') && !source.contains('&')) return source
    // A `<br>` at either end of a line is the line's own break already.
    val line = if (tableRow) source else source.replace(EDGE_BREAK, "")
    val out = StringBuilder()
    var start = 0
    INLINE_CODE.findAll(line).forEach { code ->
        out.append(cleanProse(line.substring(start, code.range.first), tableRow, closed)).append(code.value)
        start = code.range.last + 1
    }
    return out.append(cleanProse(line.substring(start), tableRow, closed)).toString()
}

/** [text] with its entities decoded and its tags dealt with; [closed] names the reply's closing tags. */
private fun cleanProse(text: String, tableRow: Boolean, closed: Set<String>): String {
    val decoded = decodeEntities(text)
    return TAG.replace(decoded) { tag ->
        val written = tag.groupValues[1]
        val name = written.lowercase()
        when {
            // Escaped already, by the model or from `&lt;`.
            decoded.getOrNull(tag.range.first - 1) == '\\' -> tag.value

            name == "br" -> if (tableRow) " " else "\n"

            isHtml(tag.value, written, tag.groupValues[2], closed) -> ""

            else -> "\\" + tag.value
        }
    }
}

/** Whether a tag [written] so, with [attributes], is HTML rather than text that looks like it. */
private fun isHtml(tag: String, written: String, attributes: String, closed: Set<String>): Boolean {
    if (written != written.lowercase() || written !in HTML_ELEMENTS) return false
    // What is left once `name="value"` pairs are taken out must be HTML's attributes that stand alone
    // (`<details open>`); `a <b and c> d` has words there instead.
    val bare = ATTRIBUTE_VALUE.replace(attributes, " ").trim().trimEnd('/')
        .split(SPACES).filter(String::isNotEmpty)
    if (bare.any { it.lowercase() !in BOOLEAN_ATTRIBUTES }) return false
    val hasAttributes = '=' in attributes || bare.isNotEmpty()
    return tag.startsWith("</") || written in EMPTY_ELEMENTS || hasAttributes || written in closed
}

/** The elements closed in the reply's prose: `</b>` in code shows code, it closes nothing. */
private fun closingTags(lines: List<String>, code: BooleanArray): Set<String> {
    val closed = mutableSetOf<String>()
    var fence: CodeFence? = null
    lines.forEachIndexed { index, line ->
        val open = fence
        val opening = if (open == null) CodeFence.openedBy(line) else null
        when {
            open != null -> if (open.closedBy(line)) fence = null
            opening != null -> fence = opening
            code[index] -> Unit
            else -> CLOSING_TAG.findAll(INLINE_CODE.replace(line, "")).forEach { closed += it.groupValues[1] }
        }
    }
    return closed
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
private val TAG = Regex("""</?([A-Za-z][A-Za-z0-9-]*)(\s[^<>]*)?/?>""")
private val CLOSING_TAG = Regex("""</([a-z][a-z0-9-]*)\s*>""")

/** An attribute with a value: `class="x"`, `width=2`. */
private val ATTRIBUTE_VALUE = Regex("""[A-Za-z_:][-\w:.]*\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'=<>`]+)""")
private val SPACES = Regex("""\s+""")

/** HTML's attributes that stand without a value. */
private val BOOLEAN_ATTRIBUTES = setOf(
    "allowfullscreen", "async", "autofocus", "autoplay", "checked", "controls", "default", "defer",
    "disabled", "download", "hidden", "inert", "ismap", "loop", "multiple", "muted", "nomodule",
    "novalidate", "open", "playsinline", "readonly", "required", "reversed", "selected",
)

/** Elements that have no closing tag. */
private val EMPTY_ELEMENTS = setOf("br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "wbr")

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
